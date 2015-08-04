/**
 * VerificationDescription.java
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright (C) Wouter Lueks, Radboud University Nijmegen, Februari 2013.
 */

package org.irmacard.credentials.info;

import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class VerificationDescription extends ConfigurationParser implements Serializable {
	private static final long serialVersionUID = 1L;

	private String verifierID;
	private String verificationID;

	private String issuerID;
	private String credentialID;

	private String name;
	private String description;
	private short id;

	private HashMap<String,Boolean> attributeDisclosed;

	private CredentialDescription credentialDescription;
	private IssuerDescription issuerDescription;

	/**
	 * The identifier of the verifier, this corresponds to the directory name in
	 * the configuration structures, and can for example be "RU" or "Surfnet".
	 */
	public String getVerifierID() {
		return verifierID;
	}

	/**
	 * The identifier of the Verification Specification, this corresponds to the
	 * directory name in the configuration structures, and can for example be
	 * "rootID".
	 */
	public String getVerificationID() {
		return verificationID;
	}

	/**
	 * The identifier of the issuer, this corresponds to the directory name in
	 * the configuration structures, and can for example be "RU" or "Surfnet".
	 */
	public String getIssuerID() {
		return issuerID;
	}

	/**
	 * The identifier of the Credential, this corresponds to the
	 * directory name in the configuration structures, and can for example be
	 * "studentCard" or "root".
	 */
	public String getCredentialID() {
		return credentialID;
	}

	/**
	 * Name of the Verification Specification, this could be "Student Card All" or
	 * some other human readable string.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Short description of what this Verification Specification will actually verify.
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Short identifier used in the backend to identify this VerificationDescription
	 */
	public short getID() {
		return id;
	}

	/**
	 * Create new credential description from file
	 * @param file Input XML file.
	 * @throws InfoException
	 */
	public VerificationDescription(URI file) throws InfoException {
		super();
		Document d = parse(file);
		init(d);
	}

	/**
	 * Create new credential description from InputStream.
	 * @param stream InputStream for XML file.
	 * @throws InfoException
	 */
	public VerificationDescription(InputStream stream) throws InfoException {
		super();
		Document d = parse(stream);
		init(d);
	}

	private void init(Document d) throws InfoException {
		name = getFirstTagText(d, "Name");
		id = (short) Integer.parseInt(getFirstTagText(d, "Id"));

		issuerID = getFirstTagText(d, "IssuerID");
		credentialID = getFirstTagText(d, "CredentialID");
		verifierID = getFirstTagText(d, "VerifierID");
		verificationID = getFirstTagText(d, "VerificationID");

		description = getFirstTagText(d, "Description");

		NodeList attrmodeList = ((Element) d.getElementsByTagName("AttributeModes")
				.item(0)).getElementsByTagName("AttributeMode");
		attributeDisclosed = new HashMap<String,Boolean>();

		for (int i = 0; i < attrmodeList.getLength(); i++) {
			Element attr = (Element) attrmodeList.item(i);
			String id = attr.getAttribute("id");
			String mode = attr.getAttribute("mode");
			boolean bdisclosed;

			// Both attributes are mandatory
			// FIXME: handle this with a verification on the XML level
			if (id.equals("")) {
				throw new InfoException(
						"AttributeMode for " + verifierID + "/" + verificationID + " is missing the id attribute");
			}
			if (mode.equals("")) {
				throw new InfoException(
						"AttributeMode for " + verifierID + "/" + verificationID + " is missing the mode attribute");
			}

			// The mode attribute must be either "revealed" or "unrevealed"
			// FIXME: handle this with a verification on the XML level
			if (mode.equals("revealed") || mode.equals("unrevealed")) {
				bdisclosed = mode.equals("revealed");
			} else {
				throw new InfoException("AttributeMode for " + verifierID + "/" + verificationID
						+ " mode should be 'revealed' or 'unrevealed'");
			}

			attributeDisclosed.put(id, bdisclosed);
		}
	}

	public String toString() {
		return "VSpec: " + verifierID + "/" + verificationID + " (" + id + ") issued by "
				+ issuerID + " as " + credentialID + " || " + disclosedSpecString();
	}

	public String disclosedSpecString() {
		String ret = "[";
		for(String attr : attributeDisclosed.keySet()) {
			ret += attr;
			if(attributeDisclosed.get(attr)) {
				ret += " (disclosed); ";
			} else {
				ret += " (undisclosed); ";
			}
		}
		return ret + "]";
	}

	/**
	 * Get the credential description. If necessary it is loaded from the DescriptionStore.
	 * @return the issuer description.
	 */
	public CredentialDescription getCredentialDescription() {
		if(credentialDescription == null) {
			try {
				credentialDescription = DescriptionStore.getInstance().getCredentialDescriptionByName(issuerID, credentialID);
			} catch (InfoException e) {
				// FIXME: for now ignore errors due to missing DescriptionStore
				e.printStackTrace();
			}
		}
		return credentialDescription;
	}

	public void updateCredentialDescription(CredentialDescription c) {
		credentialDescription = c;
	}

	/**
	 * Get the issuer description. If necessary it is loaded from the DescriptionStore.
	 * @return the issuer description.
	 */
	public IssuerDescription getIssuerDescription() {
		if(issuerDescription == null) {
			try {
				issuerDescription = DescriptionStore.getInstance().getIssuerDescription(issuerID);
			} catch (InfoException e) {
				// FIXME: for now ignore errors due to missing DescriptionStore
				e.printStackTrace();
			}
		}
		return issuerDescription;
	}

	/**
	 * Is attribute disclosed?
	 */
	public boolean isDisclosed(String attr_name) {
		return attributeDisclosed.get(attr_name);
	}
}
