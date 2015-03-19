package org.cvrgrid.hl7.fileparse.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;

public class PatientInfo {

	private final long serialVersionUID = 6721093052749142738L;
	private String firstName = "";
	private String lastName = "";
	private String birthDateTime = "";
	private String gender = "";
	private String birthplace = "";
	private String concatenation = "";
	private String hash = "";
	private LinkedList<String> variables = new LinkedList<String>();
	private LinkedList<String> locations = new LinkedList<String>();
	private boolean picuSubject = false;
	private String earliestDataPoint = "";

	public PatientInfo() {
		setFirstName("");
		setLastName("");
		setBirthDateTime("");
		setGender("");
		setBirthplace("");
	}


	/**
	 * @return the firstName
	 */
	public String getFirstName() {
		return firstName;
	}


	/**
	 * @param firstName the firstName to set
	 */
	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}


	/**
	 * @return the lastName
	 */
	public String getLastName() {
		return lastName;
	}


	/**
	 * @param lastName the lastName to set
	 */
	public void setLastName(String lastName) {
		this.lastName = lastName;
	}


	/**
	 * @return the birthDateTime
	 */
	public String getBirthDateTime() {
		return birthDateTime;
	}


	/**
	 * @param birthDateTime the birthDateTime to set
	 */
	public void setBirthDateTime(String birthDateTime) {
		this.birthDateTime = birthDateTime;
	}


	/**
	 * @return the gender
	 */
	public String getGender() {
		return gender;
	}


	/**
	 * @param gender the gender to set
	 */
	public void setGender(String gender) {
		this.gender = gender;
	}


	/**
	 * @return the birthplace
	 */
	public String getBirthplace() {
		return birthplace;
	}


	/**
	 * @param birthplace the birthplace to set
	 */
	public void setBirthplace(String birthplace) {
		this.birthplace = birthplace;
	}


	/**
	 * @return the concatenation
	 */
	public String getConcatenation() {
		if (this.concatenation.equalsIgnoreCase(""))
			setConcatenation(getFirstName()+getLastName()+getBirthDateTime()+getGender()+getBirthplace());
		return concatenation;
	}


	/**
	 * @param concatenation the concatenation to set
	 */
	private void setConcatenation(String concatenation) {
		this.concatenation = concatenation;
	}


	/**
	 * @return the hash
	 */
	public String getHash() {
		if (hash.equalsIgnoreCase(""))
			hash = setHash(this.getConcatenation());
		return hash;
	}


	/**
	 * @param hash the hash to set
	 */
	private String setHash(String hash) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] result =  sha.digest(concatenation.getBytes());
			hash = hexEncode(result);
			hash = escapeHtml(hash);
			//System.out.println("Message digest: " + hash);
		}
		catch ( NoSuchAlgorithmException ex ) {
			System.err.println(ex);
		}
		return hash;

	}
	
	/**
	 * @return the variables
	 */
	public LinkedList<String> getVariables() {
		return variables;
	}


	/**
	 * @param variables the variables to set
	 */
	public void setVariables(LinkedList<String> variables) {
		this.variables = variables;
	}

	/**
	 * @return the locations
	 */
	public LinkedList<String> getLocations() {
		return locations;
	}


	/**
	 * @param locations the locations to set
	 */
	public void setLocations(LinkedList<String> locations) {
		this.locations = locations;
	}


	/**
	 * @return the picuSubject
	 */
	public boolean isPicuSubject() {
		return picuSubject;
	}


	/**
	 * @param picuSubject the picuSubject to set
	 */
	public void setPicuSubject(boolean picuSubject) {
		this.picuSubject = picuSubject;
	}


	/**
	 * @return the earliestDataPoint
	 */
	public String getEarliestDataPoint() {
		return earliestDataPoint;
	}


	/**
	 * @param earliestDataPoint the earliestDataPoint to set
	 */
	public void setEarliestDataPoint(String earliestDataPoint) {
		this.earliestDataPoint = earliestDataPoint;
	}


	/**
	 * The byte[] returned by MessageDigest does not have a nice
	 * textual representation, so some form of encoding is usually performed.
	 *
	 * This implementation follows the example of David Flanagan's book
	 * "Java In A Nutshell", and converts a byte array into a String
	 * of hex characters.
	 *
	 * Another popular alternative is to use a "Base64" encoding.
	 **/
	private String hexEncode( byte[] aInput){
		StringBuilder result = new StringBuilder();
		char[] digits = {'0', '1', '2', '3', '4','5','6','7','8','9','a','b','c','d','e','f'};
		for ( int idx = 0; idx < aInput.length; ++idx) {
			byte b = aInput[idx];
			result.append( digits[ (b&0xf0) >> 4 ] );
			result.append( digits[ b&0x0f] );
		}
		return result.toString();
	} 

	/**
	 * Escape an html string. Escaping data received from the client helps to
	 * prevent cross-site script vulnerabilities.
	 * 
	 * @param html the html string to escape
	 * @return the escaped string
	 **/

	private String escapeHtml(String html) {
		if (html == null) {
			return null;
		}
		return html.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
	}

}
