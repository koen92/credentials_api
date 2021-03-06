/*
 * JMRTD - A Java API for accessing machine readable travel documents.
 *
 * Copyright (C) 2006 - 2012  The JMRTD team
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 * $Id: SecureMessagingWrapper.java 1370 2012-03-13 16:21:15Z martijno $
 */

package org.irmacard.credentials.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import net.sf.scuba.smartcards.APDUWrapper;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ISO7816;
import net.sf.scuba.smartcards.ProtocolCommand;
import net.sf.scuba.smartcards.ProtocolCommands;
import net.sf.scuba.smartcards.ProtocolResponse;
import net.sf.scuba.smartcards.ProtocolResponses;
import net.sf.scuba.smartcards.ResponseAPDU;
import net.sf.scuba.tlv.TLVUtil;
import net.sf.scuba.util.Hex;

/*
 * TODO: Can we use TLVInputStream instead of those readDOXX methods? -- MO
 */

/**
 * Secure messaging wrapper for apdus. Based on Section E.3 of ICAO-TR-PKI.
 *
 * @param <C> the command APDU class to use
 * @param <R> the response APDU class to use
 *
 * @author Cees-Bart Breunesse (ceesb@cs.ru.nl)
 * @author Martijn Oostdijk (martijn.oostdijk@gmail.com)
 * 
 * @version $Revision: 1370 $
 */
public class SecureMessagingWrapper implements APDUWrapper, Serializable {

	private static final String ALGORITHM = "3DES";

	private static final long serialVersionUID = -2859033943345961793L;

	private static final IvParameterSpec ZERO_IV_PARAM_SPEC = new IvParameterSpec(
			ALGORITHM.equalsIgnoreCase("AES") ? new byte[16] : new byte[8]);

	private SecretKey ksEnc, ksMac;
	private transient Cipher cipher;
	private transient Mac mac;
	private long ssc;

	/**
	 * Constructs a secure messaging wrapper based on the secure messaging
	 * session keys. The initial value of the send sequence counter is set to
	 * <code>0L</code>.
	 * 
	 * @param ksEnc
	 *            the session key for encryption
	 * @param ksMac
	 *            the session key for macs
	 * 
	 * @throws GeneralSecurityException
	 *             when the available JCE providers cannot provide the necessary
	 *             cryptographic primitives ("DESede/CBC/Nopadding" Cipher,
	 *             "ISO9797Alg3Mac" Mac).
	 */
	public SecureMessagingWrapper(SecretKey ksEnc, SecretKey ksMac)
	throws GeneralSecurityException {
		this(ksEnc, ksMac, 0L);
	}

	/**
	 * Constructs a secure messaging wrapper based on the secure messaging
	 * session keys and the initial value of the send sequence counter.
	 * 
	 * @param ksEnc
	 *            the session key for encryption
	 * @param ksMac
	 *            the session key for macs
	 * @param ssc
	 *            the initial value of the send sequence counter
	 * 
	 * @throws GeneralSecurityException
	 *             when the available JCE providers cannot provide the necessary
	 *             cryptographic primitives ("DESede/CBC/Nopadding" Cipher,
	 *             "ISO9797Alg3Mac" Mac).
	 */
	public SecureMessagingWrapper(SecretKey ksEnc, SecretKey ksMac, long ssc)
	throws GeneralSecurityException {
		this.ksEnc = ksEnc;
		this.ksMac = ksMac;
		this.ssc = ssc;

		if (ALGORITHM.equalsIgnoreCase("AES")) {
			cipher = Cipher.getInstance("AES/CBC/NoPadding");
			mac = Mac.getInstance("AESCMAC");
		} else {
			cipher = Cipher.getInstance("DESede/CBC/NoPadding");
			mac = Mac.getInstance("DESEDEMAC64");
		}
	}

	/**
	 * Gets the current value of the send sequence counter.
	 * 
	 * @return the current value of the send sequence counter.
	 */
	public/* @ pure */long getSendSequenceCounter() {
		return ssc;
	}

	/**
	 * Wraps the apdu buffer <code>capdu</code> of a command apdu.
	 * As a side effect, this method increments the internal send
	 * sequence counter maintained by this wrapper.
	 *
	 * @param commandAPDU buffer containing the command apdu.
	 *
	 * @return length of the command apdu after wrapping.
	 */
	public CommandAPDU wrap(CommandAPDU commandAPDU) {
		try {
			return wrapCommandAPDU(commandAPDU);
		} catch (GeneralSecurityException gse) {
			gse.printStackTrace();
			throw new IllegalStateException(gse.toString());
		} catch (IOException ioe) {
			ioe.printStackTrace();
			throw new IllegalStateException(ioe.toString());
		}
	}

	public void wrapAsync(ProtocolCommands commands) {
		for(ProtocolCommand c : commands) {
			c.setAPDU(wrap(c.getAPDU()));

			// Perform additional increment of the send sequence counter
			// as we will decrypt the response at a later point in time.
			ssc++;
		}
	}

	/**
	 * Unwraps the apdu buffer <code>rapdu</code> of a response apdu.
	 * 
	 * @param responseAPDU
	 *            buffer containing the response apdu.
	 * @param len
	 *            length of the actual response apdu.
	 * 
	 * @return a new byte array containing the unwrapped buffer.
	 */
	@Override
	public ResponseAPDU unwrap(ResponseAPDU responseAPDU, int len) {
		try {
			byte[] rapdu =  responseAPDU.getBytes();
			if (rapdu.length == 2) {
				// no sense in unwrapping - card indicates SM error
				throw new IllegalStateException("Card indicates SM error, SW = " + Hex.bytesToHexString(rapdu));
				/* FIXME: wouldn't it be cleaner to throw a CardServiceException? */
			}
			return new ResponseAPDU(unwrapResponseAPDU(rapdu, len));
		} catch (GeneralSecurityException gse) {
			gse.printStackTrace();
			throw new IllegalStateException(gse.toString());
		} catch (IOException ioe) {
			ioe.printStackTrace();
			throw new IllegalStateException(ioe.toString());
		}
	}

	public void unWrapAsync(ProtocolCommands commands, ProtocolResponses responses, long initial_ssc) {
		// Restore send sequence counter
		ssc = initial_ssc;

		for(ProtocolCommand c : commands) {
			ProtocolResponse r = responses.get(c.getKey());
			ResponseAPDU rapdu = r.getAPDU();
			r.setAPDU(unwrap(rapdu, rapdu.getBytes().length));

			// TODO(PV): Not sure whether this is needed 
			responses.remove(c.getKey());
			responses.put(r.getKey(), r);
			
			// Perform additional increment of the send sequence counter
			// as we will decrypt the response at a later point in time.
			ssc++;
		}
	}

	/**
	 * Does the actual encoding of a command apdu.
	 * Based on Section E.3 of ICAO-TR-PKI, especially the examples.
	 *
	 * @param capdu buffer containing the apdu data. It must be large enough
	 *             to receive the wrapped apdu.
	 * @param len length of the apdu data.
	 *
	 * @return a byte array containing the wrapped apdu buffer.
	 */
	/*@ requires apdu != null && 4 <= len && len <= apdu.length;
	 */
	private CommandAPDU wrapCommandAPDU(CommandAPDU cAcc)
	throws GeneralSecurityException, IOException {
		int lc = cAcc.getNc();
		int le = cAcc.getNe();

		ByteArrayOutputStream bOut = new ByteArrayOutputStream();

		byte[] maskedHeader = new byte[] {(byte)(cAcc.getCLA() | (byte)0x0C), (byte)cAcc.getINS(), (byte)cAcc.getP1(), (byte)cAcc.getP2()};

		byte[] paddedHeader = pad(maskedHeader);

		boolean hasDO85 = ((byte)cAcc.getINS() == ISO7816.INS_READ_BINARY2);

		byte[] do8587 = new byte[0];
		/* byte[] do8E = new byte[0]; */ /* FIXME: FindBugs told me this is a dead store -- MO */
		byte[] do97 = new byte[0];

		if (le > 0) {
			bOut.reset();
			bOut.write((byte) 0x97);
			bOut.write((byte) 0x01);
			bOut.write((byte) le);
			do97 = bOut.toByteArray();
		}

		if (lc > 0) {
			byte[] data = pad(cAcc.getData());
			cipher.init(Cipher.ENCRYPT_MODE, ksEnc, ZERO_IV_PARAM_SPEC);
			byte[] ciphertext = cipher.doFinal(data);

			bOut.reset();
			bOut.write(hasDO85 ? (byte) 0x85 : (byte) 0x87);
			bOut.write(TLVUtil.getLengthAsBytes(ciphertext.length + (hasDO85 ? 0 : 1)));
			if(!hasDO85) { bOut.write(0x01); };
			bOut.write(ciphertext, 0, ciphertext.length);
			do8587 = bOut.toByteArray();
		}

		bOut.reset();
		bOut.write(paddedHeader, 0, paddedHeader.length);
		bOut.write(do8587, 0, do8587.length);
		bOut.write(do97, 0, do97.length);
		byte[] m = bOut.toByteArray();

		bOut.reset();
		DataOutputStream dataOut = new DataOutputStream(bOut);
		ssc++;
		dataOut.writeLong(ssc);
		dataOut.write(m, 0, m.length);
		dataOut.flush();
		byte[] n = pad(bOut.toByteArray());

		/* Compute cryptographic checksum... */
		mac.init(ksMac);
		byte[] cc = mac.doFinal(n);

		bOut.reset();
		bOut.write((byte) 0x8E);
		bOut.write(cc.length);
		bOut.write(cc, 0, cc.length);
		byte[] do8E = bOut.toByteArray();

		/* Construct protected apdu... */
		bOut.reset();
		bOut.write(do8587);
		bOut.write(do97);
		bOut.write(do8E);
		byte[] data = bOut.toByteArray();

		return new CommandAPDU(maskedHeader[0], maskedHeader[1], maskedHeader[2], maskedHeader[3], data, 256);
	}

	/**
	 * Does the actual decoding of a response apdu. Based on Section E.3 of
	 * TR-PKI, especially the examples.
	 * 
	 * @param rapdu
	 *            buffer containing the apdu data.
	 * @param len
	 *            length of the apdu data.
	 * 
	 * @return a byte array containing the unwrapped apdu buffer.
	 */
	private byte[] unwrapResponseAPDU(byte[] rapdu, int len)
	throws GeneralSecurityException, IOException {
		long oldssc = ssc;
		try {
			if (rapdu == null || rapdu.length < 2 || len < 2) {
				throw new IllegalArgumentException("Invalid response APDU");
			}
			cipher.init(Cipher.DECRYPT_MODE, ksEnc, ZERO_IV_PARAM_SPEC);
			DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(rapdu));
			byte[] data = new byte[0];
			short sw = 0;
			boolean finished = false;
			byte[] cc = null;
			while (!finished) {
				int tag = inputStream.readByte();
				switch (tag) {
				case (byte) 0x87:
					data = readDO87(inputStream, false);
				break;
				case (byte) 0x85:
					data = readDO87(inputStream, true);
				break;
				case (byte) 0x99:
					sw = readDO99(inputStream);
				break;
				case (byte) 0x8E:
					cc = readDO8E(inputStream);
				finished = true;
				break;
				}
			}
			if (!checkMac(rapdu, cc)) {
				throw new IllegalStateException("Invalid MAC");
			}
			ByteArrayOutputStream bOut = new ByteArrayOutputStream();
			bOut.write(data, 0, data.length);
			bOut.write((sw & 0xFF00) >> 8);
			bOut.write(sw & 0x00FF);
			return bOut.toByteArray();
		} finally {
			/*
			 * If we fail to unwrap, at least make sure we have the same counter
			 * as the ICC, so that we can continue to communicate using secure
			 * messaging...
			 */
			if (ssc == oldssc) {
				ssc++;
			}
		}
	}

	/**
	 * The <code>0x87</code> tag has already been read.
	 * 
	 * @param inputStream
	 *            inputstream to read from.
	 */
	private byte[] readDO87(DataInputStream inputStream, boolean do85) throws IOException,
	GeneralSecurityException {
		/* Read length... */
		int length = 0;
		int buf = inputStream.readUnsignedByte();
		if ((buf & 0x00000080) != 0x00000080) {
			/* Short form */
			length = buf;
			if(!do85) {
				buf = inputStream.readUnsignedByte(); /* should be 0x01... */
				if (buf != 0x01) {
					throw new IllegalStateException(
							"DO'87 expected 0x01 marker, found "
							+ Hex.byteToHexString((byte) buf));
				}
			}
		} else {
			/* Long form */
			int lengthBytesCount = buf & 0x0000007F;
			for (int i = 0; i < lengthBytesCount; i++) {
				length = (length << 8) | inputStream.readUnsignedByte();
			}
			if(!do85) {
				buf = inputStream.readUnsignedByte(); /* should be 0x01... */
				if (buf != 0x01) {
					throw new IllegalStateException("DO'87 expected 0x01 marker");
				}
			}
		}
		if(!do85) {
			length--; /* takes care of the extra 0x01 marker... */
		}
		/* Read, decrypt, unpad the data... */
		byte[] ciphertext = new byte[length];
		inputStream.readFully(ciphertext);
		byte[] paddedData = cipher.doFinal(ciphertext);
		byte[] data = unpad(paddedData);
		return data;
	}

	/**
	 * The <code>0x99</code> tag has already been read.
	 * 
	 * @param in
	 *            inputstream to read from.
	 */
	private short readDO99(DataInputStream in) throws IOException {
		int length = in.readUnsignedByte();
		if (length != 2) {
			throw new IllegalStateException("DO'99 wrong length");
		}
		byte sw1 = in.readByte();
		byte sw2 = in.readByte();
		return (short) (((sw1 & 0x000000FF) << 8) | (sw2 & 0x000000FF));
	}

	/**
	 * The <code>0x8E</code> tag has already been read.
	 * 
	 * @param in
	 *            inputstream to read from.
	 */
	private byte[] readDO8E(DataInputStream in) throws IOException,
	GeneralSecurityException {
		int length = in.readUnsignedByte();
		if (length != 8) {
			throw new IllegalStateException("DO'8E wrong length");
		}
		byte[] cc1 = new byte[8];
		in.readFully(cc1);
		return cc1;
	}

	private boolean checkMac(byte[] rapdu, byte[] cc1)
	throws GeneralSecurityException {
		try {
			ByteArrayOutputStream bOut = new ByteArrayOutputStream();
			DataOutputStream dataOut = new DataOutputStream(bOut);
			ssc++;
			dataOut.writeLong(ssc);
			byte[] paddedData = pad(rapdu, 0, rapdu.length - 2 - 8 - 2);
			dataOut.write(paddedData, 0, paddedData.length);
			dataOut.flush();
			mac.init(ksMac);
			byte[] cc2 = mac.doFinal(bOut.toByteArray());
			dataOut.close();
			return Arrays.equals(cc1, cc2);
		} catch (IOException ioe) {
			return false;
		}
	}
	
	/**
	 * Pads the input <code>in</code> according to ISO9797-1 padding method 2.
	 *
	 * @param in input
	 *
	 * @return padded output
	 */
	public static byte[] pad(/*@ non_null */ byte[] in) {
		return pad(in, 0, in.length);
	}

	/*@ requires 0 <= offset && offset < length;
	  @ requires 0 <= length && length <= in.length;
	 */
	public static byte[] pad(/*@ non_null */ byte[] in,
			int offset, int length) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(in, offset, length);
		out.write((byte)0x80);
		while (out.size() % 8 != 0) {
			out.write((byte)0x00);
		}
		return out.toByteArray();
	}

	public static byte[] unpad(byte[] in) {
		int i = in.length - 1;
		while (i >= 0 && in[i] == 0x00) {
			i--;
		}
		if ((in[i] & 0xFF) != 0x80) {
			throw new IllegalStateException("unpad expected constant 0x80, found 0x" + Integer.toHexString((in[i] & 0x000000FF)) + "\nDEBUG: in = " + Hex.bytesToHexString(in) + ", index = " + i);
		}
		byte[] out = new byte[i];
		System.arraycopy(in, 0, out, 0, i);
		return out;
	}
}
