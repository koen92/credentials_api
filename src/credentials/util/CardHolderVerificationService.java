package credentials.util;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

import javax.smartcardio.CardException;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

import net.sourceforge.scuba.util.Hex;

import net.sourceforge.scuba.smartcards.CardService;
import net.sourceforge.scuba.smartcards.CardServiceException;
import net.sourceforge.scuba.smartcards.CommandAPDU;
import net.sourceforge.scuba.smartcards.ICommandAPDU;
import net.sourceforge.scuba.smartcards.IResponseAPDU;
import net.sourceforge.scuba.smartcards.TerminalCardService;

public class CardHolderVerificationService extends CardService {

	private static final long serialVersionUID = -7992986822145276115L;

	public final static int PIN_OK = 1000;
	
    static final String[] FEATURES = new String[]{"NO_FEATURE",
        "FEATURE_VERIFY_PIN_START",
        "FEATURE_VERIFY_PIN_FINISH",
        "FEATURE_MODIFY_PIN_START",
        "FEATURE_MODIFY_PIN_FINISH",
        "FEATURE_GET_KEY_PRESSED",
        "FEATURE_VERIFY_PIN_DIRECT",
        "FEATURE_MODIFY_PIN_DIRECT",
        "FEATURE_MCT_READER_DIRECT",
        "FEATURE_MCT_UNIVERSAL",
        "FEATURE_IFD_PIN_PROPERTIES",
        "FEATURE_ABORT",
        "FEATURE_SET_SPE_MESSAGE",
        "FEATURE_VERIFY_PIN_DIRECT_APP_ID",
        "FEATURE_MODIFY_PIN_DIRECT_APP_ID",
        "FEATURE_WRITE_DISPLAY",
        "FEATURE_GET_KEY",
        "FEATURE_IFD_DISPLAY_PROPERTIES"};
    static final Byte FEATURE_VERIFY_PIN_START = new Byte((byte) 0x01);
    static final Byte FEATURE_VERIFY_PIN_FINISH = new Byte((byte) 0x02);
    static final Byte FEATURE_MODIFY_PIN_START = new Byte((byte) 0x03);
    static final Byte FEATURE_MODIFY_PIN_FINISH = new Byte((byte) 0x04);
    static final Byte FEATURE_GET_KEY_PRESSED = new Byte((byte) 0x05);
    static final Byte FEATURE_VERIFY_PIN_DIRECT = new Byte((byte) 0x06);
    static final Byte FEATURE_MODIFY_PIN_DIRECT = new Byte((byte) 0x07);
    static final Byte FEATURE_MCT_READER_DIRECT = new Byte((byte) 0x08);
    static final Byte FEATURE_MCT_UNIVERSAL = new Byte((byte) 0x09);
    static final Byte FEATURE_IFD_PIN_PROPERTIES = new Byte((byte) 0x0a);
    HashMap<Byte, Integer> features;
    byte bEntryValidationCondition = 0x02;  // validation key pressed
    byte bTimeOut = 0x00;                   // 0x3c;                   // 60sec (= max on ReinerSCT)
    byte bTimeOut2 = 0x00;                  // default (attention with SCM)
    byte wPINMaxExtraDigitMin = 0x00;         // min pin length zero digits
    byte wPINMaxExtraDigitMax = 0x04;         // max pin length 12 digits
    
	private TerminalCardService service;
	
	public CardHolderVerificationService(TerminalCardService service) {
		this.service = service;
	}

	public void open() throws CardServiceException {
		service.open();
	}

	public boolean isOpen() {
		return service.isOpen();
	}

	public IResponseAPDU transmit(ICommandAPDU capdu)
	throws CardServiceException {
		return service.transmit(capdu);
	}

	public void close() {
		service.close();
	}

    public int verifyPIN() 
    throws CardServiceException {
        queryFeatures();
        
        if (features.containsKey(FEATURE_VERIFY_PIN_DIRECT)) {
            return verifyPinUsingPinpad();
        } else {
            return verifyPinUsingDialog();
        }
    }

    private int verifyPinUsingDialog() 
    throws CardServiceException {
        // ask for pin, inform the user
        JPasswordField pinField=new JPasswordField(6);
        JLabel lab=new JLabel("The server requests to authenticate your identity, enter PIN:");
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        GridBagConstraints cc = new GridBagConstraints();
        cc.anchor = GridBagConstraints.WEST;
        cc.insets = new Insets(10, 10, 10, 10);
        cc.gridx = 0; cc.gridy = 0;
        panel.add(lab, cc);
        cc.gridy++;
        panel.add(pinField, cc);
        int result = JOptionPane.showConfirmDialog(null, panel, "PIN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        String pinString = new String(pinField.getPassword());
        if(result != 0 || pinString.length() != 4) {
            throw new CardServiceException("PIN not entered or does not meet requirements");                                        
        }
        ICommandAPDU c = new CommandAPDU(0, 0x20, 0, 0, pinString.getBytes());
        System.out.println("C: " + Hex.toHexString(c.getBytes()));
        IResponseAPDU r = service.transmit(c);
        System.out.println("R: " + Hex.toHexString(r.getBytes()));
        if(r.getSW() == 0x9000) {
        	return PIN_OK;
        } else if ((r.getSW() & 0xFFF0) == 0x63C0) {
    		return (r.getSW() & 0x000F);
    	} else {
    		throw new CardServiceException("PIN verification failed: " + Hex.bytesToHexString(r.getBytes()));
    	}
    }

    private int verifyPinUsingPinpad() 
    throws CardServiceException {
    	JDialog dialog;
        try {
            setUpReader();
            JLabel label = new JLabel("<html>The server requests to authenticate your identity.<br><br>Please enter your PIN using the pinpad of the reader.</html>");
            JOptionPane pane = new JOptionPane(label, JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION);
            dialog = pane.createDialog("PIN");
            dialog.setModal(false);
            dialog.pack();
            dialog.setVisible(true);
        } catch (Exception e) {
        	e.printStackTrace();
        	throw new CardServiceException("PIN verification failed: " + e.getMessage());
        }
        int sw = Integer.parseInt(Hex.toHexString(VERIFY_PIN_DIRECT()), 16);
        dialog.setVisible(false);
        if (sw == 0x9000) {
            return PIN_OK;
        } else if ((sw & 0xFFF0) == 0x63C0) {
    		return (sw & 0x000F);
    	} else {
    		throw new CardServiceException("PIN verification failed: " + Hex.intToHexString(sw));
    	}
    }

    private static int SCARD_CTL_CODE(int code) {
        int ioctl;
        String os_name = System.getProperty("os.name").toLowerCase();
        if (os_name.indexOf("windows") > -1) {
            ioctl = (0x31 << 16 | (code) << 2);
        } else {
            ioctl = 0x42000000 + (code);
        }
        return ioctl;
    }

    static int IOCTL_GET_FEATURE_REQUEST = SCARD_CTL_CODE(3400);

    protected void setUpReader() throws IOException, NoSuchAlgorithmException,
            CardException {

        String os = System.getProperty("os.name");

        boolean pcsclite = os.toLowerCase().indexOf("windows") < 0;

        String name = service.getTerminal().getName().toLowerCase();

        if (name.startsWith("gemplus gempc pinpad")
                || name.startsWith("gemalto gempc pinpad")) {
            /*
             * Gemplus Pinpad - VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:00
             * :02:01:09:04:00:00:00:00:0d:00:00:00:00:20:00
             * :01:08:20:ff:ff:ff:ff:ff:ff:ff] Linux(?):
             * transmitControlCommand() failed:
             * sun.security.smartcardio.PCSCException: SCARD_E_NOT_TRANSACTED
             * Win7: [6b:80] - VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:08:04
             * :02:01:09:04:00:00:00:00:0d:00:00:00:00:20:00
             * :01:08:20:ff:ff:ff:ff:ff:ff:ff] Linux(?): response [64:00]
             * (18154msec) Win7 (mit bTimeOut 0x3c): [00:40:02:90:00:d2]
             */
            wPINMaxExtraDigitMin = 0x04;
            wPINMaxExtraDigitMax = 0x08;
        } else if (name.startsWith("reiner-sct cyberjack pinpad(a)")) {
            // Reiner-SCT cyberJack pinpad(a) (2242245778) 00 00
            /*
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:00:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * response [64:00] (14994msec)
             */
        } else if (name.startsWith("reiner sct cyberjack")) {
            // REINER SCT CyberJack 00 00
            /*
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:00:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * response [67:00]
             */
            // if (pcsclite) {
            // TODO fallback to DefaultReader
            // report.write("setting custom bTimeOut (0x0f) for " + name);
            // bTimeOut = 0x0f;
            // report.write("setting custom bTimeOut2 (0x0f) for " + name);
            // bTimeOut2 = 0x0f;
            // report.write("setting custom wPINMaxExtraDigitMin (0x04) for " +
            // name);
            // wPINMaxExtraDigitMin = 0x04;
            // report.write("setting custom wPINMaxExtraDigitMax (0x08) for " +
            // name);
            // wPINMaxExtraDigitMax = 0x08;
            // }
        } else if (name.startsWith("omnikey cardman 3621")) {
            // OmniKey CardMan 3621 00 00
            /*
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:00:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * response [90:00] (5204msec)
             */
            // log.debug("setting custom wPINMaxExtraDigitH (0x01) for " +
            // name);
            // wPINMaxExtraDigitH = 0x01;
        } else if (name.startsWith("scm spr 532")
                || name.startsWith("scm microsystems inc. sprx32 usb smart card reader")) {
            // SCM SPR 532 (60200DC5) 00 00
            /*
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:00:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * transmitControlCommand() failed:
             * sun.security.smartcardio.PCSCException: SCARD_E_NOT_TRANSACTED
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:01:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * response [64:00] (15543msec)
             */
            if (pcsclite) {
                wPINMaxExtraDigitMin = 0x01;
            }
        } else if (name.startsWith("cherry smartboard xx44")) {
            if (pcsclite) {
                wPINMaxExtraDigitMin = 0x01;
            }
        } else if (name.startsWith("cherry smartterminal st-2xxx")) {
            // Cherry SmartTerminal ST-2XXX (21121010102014) 00 00
            /*
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:00:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * transmitControlCommand() failed:
             * sun.security.smartcardio.PCSCException: SCARD_E_NOT_TRANSACTED
             * VERIFY_PIN_DIRECT (42330006)
             * [00:00:89:47:04:0c:01:02:01:09:04:00:
             * 00:00:00:0d:00:00:00:00:20:00:01:08:20:ff:ff:ff:ff:ff:ff:ff]
             * response [64:00] (15358msec)
             */
            if (pcsclite) {
                wPINMaxExtraDigitMin = 0x01;
            }
        }
    }

    protected void queryFeatures() throws CardServiceException {
        byte[] resp = service.transmitControlCommand(IOCTL_GET_FEATURE_REQUEST,
                new byte[0]);

        features = new HashMap<Byte, Integer>();

        for (int i = 0; i < resp.length; i += 6) {
            Byte feature = new Byte(resp[i]);
            Integer ioctl = new Integer((0xff & resp[i + 2]) << 24)
                    | ((0xff & resp[i + 3]) << 16)
                    | ((0xff & resp[i + 4]) << 8) | (0xff & resp[i + 5]);
            features.put(feature, ioctl);
        }
    }

    protected byte[] VERIFY_PIN_DIRECT() throws CardServiceException {
        byte[] PIN_VERIFY = createPINVerifyStructure();
        int ioctl = features.get(FEATURE_VERIFY_PIN_DIRECT);
        return service.transmitControlCommand(ioctl, PIN_VERIFY);
    }

    protected byte[] createPINVerifyStructure() {

        // VerifyAPDUSpec apduSpec = new VerifyAPDUSpec(
        byte[] apdu = new byte[] { (byte) 0x00, (byte) 0x20, (byte) 0x00,
                (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00 };
        // 1, VerifyAPDUSpec.PIN_FORMAT_BCD, 7, 4, 4);

        ByteArrayOutputStream s = new ByteArrayOutputStream();
        // bTimeOut
        s.write(bTimeOut);
        // bTimeOut2
        s.write(bTimeOut2);
        // bmFormatString [10001001 0x89]
        s.write(0x82);
//        s.write(1 << 7 // system unit = byte
//                | (0xF & 1) << 3 // apduSpec.getPinPosition() (0001 ... pin 1
//                                 // byte after format)
//                | (0x1 & 0 << 2) // apduSpec.getPinJustification() (0 ... left
//                                 // justify)
//                | (0x3 & 1)); // apduSpec.getPinFormat() (01 ... BCD)
        // bmPINBlockString [01000111 0x47]
        s.write(0x04);
//        s.write((0xF & 4) << 4 // apduSpec.getPinLengthSize() (0100 ... 4 bit
//                               // pin length)
//                | (0xF & 7)); // apduSpec.getPinLength() (0111 ... 7 bytes pin
//                              // block size)
        // bmPINLengthFormat [00000100 0x04]
        s.write(0x00);
//        s.write(// system unit = bit
//        (0xF & 4)); // apduSpec.getPinLengthPos() (00000100 ... pin length
//                    // position 4 bits)
        // wPINMaxExtraDigit (little endian) [0x0c 0x00]
        s.write(wPINMaxExtraDigitMax); // max PIN length
        s.write(wPINMaxExtraDigitMin); // min PIN length
        // bEntryValidationCondition [0x02]
        s.write(bEntryValidationCondition);
        // bNumberMessage
        s.write(0x01);
        // wLangId [0x04 0x09 english, little endian]
        s.write(0x04);
        s.write(0x09);
        // bMsgIndex
        s.write(0x01);
        // bTeoPrologue
        s.write(0x00);
        s.write(0x00);
        s.write(0x00);
        // ulDataLength
        s.write(apdu.length);
        s.write(0x00);
        s.write(0x00);
        s.write(0x00);
        // abData
        try {
            s.write(apdu);
        } catch (IOException e) {
            // As we are dealing with ByteArrayOutputStreams no exception is to
            // be
            // expected.
            throw new RuntimeException(e);
        }

        return s.toByteArray();
    }
}