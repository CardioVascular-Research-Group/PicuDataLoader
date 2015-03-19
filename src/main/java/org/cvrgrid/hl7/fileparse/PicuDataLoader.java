/* Copyright 2015 Cardiovascular Research Grid
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 * 
 *	All rights reserved
 * 	
 * 	@author Stephen J Granite (Email: sgranite@jhu.edu)
 */

package org.cvrgrid.hl7.fileparse;

/*
 * This is the main class to take PICU HL7 data, filter it based upon location, generate Global Unique 
 * Identifiers (GUIDs) for the subjects, load the time series information into OpenTSDB and maintain a 
 * lookup file for the GUIDs and a log file for HL7 files processed with the original HL7 files.
 * It uses information in a local server.properties file.  This information tells the tool: 1) where 
 * to get HL7 translation information from, 2) where to maintain the lookup and the log files and 3) w
 * here the OpenTSDB server is located, to store the time-series data with the GUID information in a tag.
 * 
 * The tool itself is a Java command line tool, intended to be placed in a service job that runs at a
 * specific interval.  The tool does not need to be co-located with the data, as the server.properties
 * specifies the path information for the data.  The tool begins by loading values for HL7 translation.
 * Then the tool loads information for existing subjects from the lookup.  Then the tool loads information
 * from the log file, to know what HL7 files to process.  After all this pre-loading, the tool then scours
 * the root directory for HL7 data.  During this process, the tool checks to see if the data was already 
 * processing, through comparison to the log file's contents.  If there are no new data, the tool stops.
 * Otherwise, the tool parses the new data, loading the time series into OpenTSDB with GUIDs stored in a
 * subjectId tag.  While loading the time series, the tool appends information to the lookup and the log
 * datasets.  Upon completion of processing, the tool generates new lookup and log files, to sit for the 
 * next processing check cycle.
 * 
 * The tool requires the Apache POI libraries to work with Excel files, the Apache Camel and HL7 API 
 * libraries to work with HL7 and the CVRG OpenTSDB client to work with OpenTSDB.  All these dependencies 
 * are stored in the pom.xml.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.cvrgrid.hl7.fileparse.model.HL7Measurements;
import edu.jhu.cvrg.timeseriesstore.model.IncomingDataPoint;
import edu.jhu.cvrg.timeseriesstore.opentsdb.store.OpenTSDBTimeSeriesStorer;

import org.cvrgrid.hl7.fileparse.model.OpenTSDBConfiguration;
import org.cvrgrid.hl7.fileparse.model.PatientInfo;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v23.message.ORU_R01;
import ca.uhn.hl7v2.util.Hl7InputStreamMessageIterator;
import ca.uhn.hl7v2.util.Terser;

public class PicuDataLoader { 

	private String configFilename = "/resources/server.properties";
	private OpenTSDBConfiguration openTSDBConfiguration = new OpenTSDBConfiguration();

	/**
	 * Constructor for this code intended to set all the variables based upon the properties file.
	 */
	public PicuDataLoader(){		 

		try {

			OpenTSDBConfiguration openTSDBConfiguration = new OpenTSDBConfiguration();
			Properties serverProperties = new Properties();
			InputStream stream = PicuDataLoader.class.getResourceAsStream(this.getConfigFilename());
			serverProperties.load(stream);
			openTSDBConfiguration.setOpenTSDBUrl(serverProperties.getProperty("openTSDBUrl"));
			openTSDBConfiguration.setApiPut(serverProperties.getProperty("apiPut"));
			openTSDBConfiguration.setApiQuery(serverProperties.getProperty("apiQuery"));
			openTSDBConfiguration.setAwareSupportedParams(serverProperties.getProperty("awareSupportedParams"));
			openTSDBConfiguration.setIdMatch(serverProperties.getProperty("idMatch"));
			openTSDBConfiguration.setIdMatchSheet(serverProperties.getProperty("idMatchSheet"));
			openTSDBConfiguration.setProcessedFile(serverProperties.getProperty("processedFile"));
			openTSDBConfiguration.setRootDir(serverProperties.getProperty("rootDir"));
			openTSDBConfiguration.setFolderPath(serverProperties.getProperty("folderPath"));
			openTSDBConfiguration.setStudyString(serverProperties.getProperty("studyString"));
			this.setOpenTSDBConfiguration(openTSDBConfiguration);

		} catch (IOException e) {

			e.printStackTrace();

		}

	}

	public static void main(String[] args) throws Exception {

		PicuDataLoader hashMaster = new PicuDataLoader();
		SimpleDateFormat fromUser = new SimpleDateFormat("yyyyMMddHHmmss");
		SimpleDateFormat myFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		OpenTSDBConfiguration openTSDBConfiguration = hashMaster.getOpenTSDBConfiguration();
		String urlString = openTSDBConfiguration.getOpenTSDBUrl();
		HL7Measurements hl7Measurements = new HL7Measurements();
		HashMap<String,String> measurementNames = hl7Measurements.getMeasurementNames();
		XSSFWorkbook wb = readFile(openTSDBConfiguration.getAwareSupportedParams());
		XSSFSheet sheet = wb.getSheetAt(0);
		for (int r = 1; r < 280; r++) {
			XSSFRow row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			measurementNames.put(row.getCell(2).getStringCellValue(), row.getCell(1).getStringCellValue());
		}
		HashMap<String,PatientInfo> idMatch = new HashMap<String,PatientInfo>();
		File f = new File(openTSDBConfiguration.getIdMatch());
		if (f.exists()) {
			wb = readFile(openTSDBConfiguration.getIdMatch());
			sheet = wb.getSheetAt(0);
			for (int r = 1; r < sheet.getLastRowNum()+1; r++) {
				XSSFRow row = sheet.getRow(r);
				PatientInfo patInfo = new PatientInfo();
				patInfo.setPicuSubject(row.getCell(1).getBooleanCellValue());
				patInfo.setFirstName(row.getCell(3).getStringCellValue());
				patInfo.setLastName(row.getCell(4).getStringCellValue());
				patInfo.setBirthDateTime(row.getCell(5).getStringCellValue());
				patInfo.setGender(row.getCell(6).getStringCellValue());
				patInfo.setBirthplace(row.getCell(7).getStringCellValue());
				patInfo.setEarliestDataPoint(row.getCell(8).getStringCellValue());
				LinkedList<String> locations = new LinkedList<String>();
				String lSet = row.getCell(10).getStringCellValue();
				lSet = lSet.replaceAll("\\[", "");
				lSet = lSet.replaceAll("\\]", "");				
				String[] locationSet = lSet.split(",");
				for (String location : locationSet) {
					locations.add(location);
				}
				patInfo.setLocations(locations);
				LinkedList<String> variables = new LinkedList<String>();
				String vSet = row.getCell(12).getStringCellValue();
				vSet = vSet.replaceAll("\\[", "");
				vSet = vSet.replaceAll("\\]", "");				
				String[] variableSet = vSet.split(",");
				for (String variable : variableSet) {
					variables.add(variable);
				}
				patInfo.setVariables(variables);
				idMatch.put(patInfo.getHash(),patInfo);
			}
		}
		System.out.println("Existing Subject Count: " + idMatch.size());
		String processedFile = openTSDBConfiguration.getProcessedFile();
		String rootDir = openTSDBConfiguration.getRootDir();
		ArrayList<String> processedFiles = new ArrayList<String>();
		File processedFileContents = new File (processedFile);
		getProcessedFiles(processedFileContents, processedFiles);
		ArrayList<String> messageFiles = new ArrayList<String>();
		File rootDirContents = new File (rootDir);
		getDirectoryContents(rootDirContents, processedFiles, messageFiles);
		XSSFWorkbook workbook;
		XSSFSheet sheetOut, sheetOut2;
		if (processedFiles.size() > 1) {
			workbook = readFile(openTSDBConfiguration.getIdMatch());
			sheetOut = workbook.getSheetAt(0);
			sheetOut2 = workbook.getSheetAt(1);
		} else {
			workbook = new XSSFWorkbook();
			sheetOut = workbook.createSheet("idMatch");
			sheetOut2 = workbook.createSheet(openTSDBConfiguration.getIdMatchSheet());
		}
		for (String filePath : messageFiles) {
			System.out.println("     File: " + filePath);
			FileReader reader = new FileReader(filePath);

			Hl7InputStreamMessageIterator iter = new Hl7InputStreamMessageIterator(reader);

			while (iter.hasNext()) {
				HashMap<String,String> tags = new HashMap<String,String>();
				Message next = iter.next();
				ORU_R01 oru = new ORU_R01();
				oru.parse(next.encode());
				PatientInfo patInfo = new PatientInfo();
				if (Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 5, 0, 2, 1) != null)
					patInfo.setFirstName(Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 5, 0, 2, 1).trim());
				if (Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 5, 0, 1, 1) != null)
					patInfo.setLastName(Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 5, 0, 1, 1).trim());
				if (Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 7, 0, 1, 1) != null)
					patInfo.setBirthDateTime(Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 7, 0, 1, 1).trim());
				if (Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 8, 0, 1, 1) != null)
					patInfo.setGender(Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 8, 0, 1, 1).trim());
				if (Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 23, 0, 1, 1) != null)
					patInfo.setBirthplace(Terser.get(oru.getRESPONSE().getPATIENT().getPID(), 23, 0, 1, 1).trim());
				LinkedList<String> locations = new LinkedList<String>();
				LinkedList<String> variables = new LinkedList<String>();
				if (idMatch.get(patInfo.getHash()) != null) {
					patInfo = idMatch.get(patInfo.getHash());
					locations = patInfo.getLocations();
					variables = patInfo.getVariables();
				}
				if (!locations.contains(Terser.get(oru.getRESPONSE().getPATIENT().getVISIT().getPV1(), 3, 0, 1, 1))) {
					locations.add(Terser.get(oru.getRESPONSE().getPATIENT().getVISIT().getPV1(), 3, 0, 1, 1));
					if (locations.peekLast().startsWith("ZB04"))
						patInfo.setPicuSubject(true);
				}
				tags.put("subjectId", patInfo.getHash());
				String time = Terser.get(oru.getRESPONSE().getORDER_OBSERVATION().getOBR(), 7, 0, 1, 1);
				Date timepoint = fromUser.parse(time);
				String reformattedTime = myFormat.format(timepoint);
				if (patInfo.getEarliestDataPoint().equalsIgnoreCase("")) {
					patInfo.setEarliestDataPoint(reformattedTime);
				}
				List<ORU_R01_OBSERVATION> observations = oru.getRESPONSE().getORDER_OBSERVATION().getOBSERVATIONAll();
				for (ORU_R01_OBSERVATION observation : observations) {
					String seriesName = Terser.get(observation.getOBX(), 3, 0, 1, 1);
					if (measurementNames.get(seriesName) != null) {
						seriesName = measurementNames.get(seriesName);
					} else {
						seriesName = seriesName.replaceFirst("\\d", "#");
						seriesName = measurementNames.get(seriesName);
					}
					
			        StringBuffer buff = new StringBuffer();

			        String[] tokens = seriesName.split(" ");
			        for (String i : tokens) {
			            buff.append(StringUtils.capitalize(i));
			        }

					String measurementValue = Terser.get(observation.getOBX(), 5, 0, 1, 1);
					String units = Terser.get(observation.getOBX(), 6, 0, 1, 1);
					if (units != null) {
						units = units.replaceAll("min", "Min");
						units = units.replaceAll("/", "Per");
						units = units.replaceAll("%", "percent");
						units = units.replaceAll("#", "Count");
						units = units.replaceAll("cel", "Celsius");
						units = units.replaceAll("mm\\(hg\\)", "mmHg");
					}
					seriesName = "vitals." + StringUtils.uncapitalize(units);
					seriesName += "." + StringUtils.uncapitalize(buff.toString());
					if (!variables.contains(seriesName))
						variables.add(seriesName);
					IncomingDataPoint dataPoint = new IncomingDataPoint(seriesName, timepoint.getTime(), measurementValue, tags);
					OpenTSDBTimeSeriesStorer.storeTimePoint(urlString, dataPoint);
				}
				patInfo.setLocations(locations);
				patInfo.setVariables(variables);
				idMatch.put(patInfo.getHash(), patInfo);
			}
			System.out.println("     Subject Count: " + idMatch.size());
			int rowNum = 0;
			Set<String> keys = idMatch.keySet();
			TreeSet<String> sortedKeys = new TreeSet<String>(keys);
			for (String key : sortedKeys) {
				XSSFRow row = sheetOut.createRow(rowNum);
				XSSFRow row2 = sheetOut2.createRow(rowNum);
				XSSFCell cell, cell2;
				if (rowNum == 0) {
					cell = row.createCell(0);
					cell.setCellValue("Count");
					cell = row.createCell(1);
					cell.setCellValue("PICU Subject?");
					cell = row.createCell(2);
					cell.setCellValue("Hash");
					cell = row.createCell(3);
					cell.setCellValue("First Name");
					cell = row.createCell(4);
					cell.setCellValue("Last Name");
					cell = row.createCell(5);
					cell.setCellValue("Birth Date/Time");
					cell = row.createCell(6);
					cell.setCellValue("Gender");
					cell = row.createCell(7);
					cell.setCellValue("Birthplace");
					cell = row.createCell(8);
					cell.setCellValue("First Time Point");
					cell = row.createCell(9);
					cell.setCellValue("Location Count");
					cell = row.createCell(10);
					cell.setCellValue("Locations");
					cell = row.createCell(11);
					cell.setCellValue("Variable Count");
					cell = row.createCell(12);
					cell.setCellValue("Variables");
					cell2 = row2.createCell(0);
					cell2.setCellValue("Count");
					cell2 = row2.createCell(1);
					cell2.setCellValue("PICU Subject?");
					cell2 = row2.createCell(2);
					cell2.setCellValue("Hash");
					cell2 = row2.createCell(3);
					cell2.setCellValue("First Name");
					cell2 = row2.createCell(4);
					cell2.setCellValue("Last Name");
					cell2 = row2.createCell(5);
					cell2.setCellValue("Birth Date/Time");
					cell2 = row2.createCell(6);
					cell2.setCellValue("Gender");
					cell2 = row2.createCell(7);
					cell2.setCellValue("Birthplace");
					cell2 = row2.createCell(8);
					cell2.setCellValue("First Time Point");
					cell2 = row2.createCell(9);
					cell2.setCellValue("Location Count");
					cell2 = row2.createCell(10);
					cell2.setCellValue("Locations");
					cell2 = row2.createCell(11);
					cell2.setCellValue("Variable Count");
					cell2 = row2.createCell(12);
					cell2.setCellValue("Variables");
				} else {
					cell = row.createCell(0);
					cell.setCellValue(rowNum);
					cell = row.createCell(1);
					cell.setCellValue(idMatch.get(key).isPicuSubject());
					cell = row.createCell(2);
					cell.setCellValue(key);
					cell = row.createCell(3);
					cell.setCellValue(idMatch.get(key).getFirstName());
					cell = row.createCell(4);
					cell.setCellValue(idMatch.get(key).getLastName());
					cell = row.createCell(5);
					cell.setCellValue(idMatch.get(key).getBirthDateTime());
					cell = row.createCell(6);
					cell.setCellValue(idMatch.get(key).getGender());
					cell = row.createCell(7);
					cell.setCellValue(idMatch.get(key).getBirthplace());
					cell = row.createCell(8);
					cell.setCellValue(idMatch.get(key).getEarliestDataPoint());
					cell = row.createCell(9);
					cell.setCellValue(idMatch.get(key).getLocations().size());
					cell = row.createCell(10);
					cell.setCellValue(idMatch.get(key).getLocations().toString());
					cell = row.createCell(11);
					cell.setCellValue(idMatch.get(key).getVariables().size());
					cell = row.createCell(12);
					cell.setCellValue(idMatch.get(key).getVariables().toString());
					if (idMatch.get(key).isPicuSubject()) {
						cell2 = row2.createCell(0);
						cell2.setCellValue(rowNum);
						cell2 = row2.createCell(1);
						cell2.setCellValue(idMatch.get(key).isPicuSubject());
						cell2 = row2.createCell(2);
						cell2.setCellValue(key);
						cell2 = row2.createCell(3);
						cell2.setCellValue(idMatch.get(key).getFirstName());
						cell2 = row2.createCell(4);
						cell2.setCellValue(idMatch.get(key).getLastName());
						cell2 = row2.createCell(5);
						cell2.setCellValue(idMatch.get(key).getBirthDateTime());
						cell2 = row2.createCell(6);
						cell2.setCellValue(idMatch.get(key).getGender());
						cell2 = row2.createCell(7);
						cell2.setCellValue(idMatch.get(key).getBirthplace());
						cell2 = row2.createCell(8);
						cell2.setCellValue(idMatch.get(key).getEarliestDataPoint());
						cell2 = row2.createCell(9);
						cell2.setCellValue(idMatch.get(key).getLocations().size());
						cell2 = row2.createCell(10);
						cell2.setCellValue(idMatch.get(key).getLocations().toString());
						cell2 = row2.createCell(11);
						cell2.setCellValue(idMatch.get(key).getVariables().size());
						cell2 = row2.createCell(12);
						cell2.setCellValue(idMatch.get(key).getVariables().toString());	
					}
				}
				rowNum++;
			}
		}

		if (messageFiles.size() > 0) {
			try {

				FileOutputStream out = new FileOutputStream(new File(openTSDBConfiguration.getIdMatch()));
				workbook.write(out);
				out.close();
				System.out.println("Excel written successfully...");
				PrintWriter writer = new PrintWriter(rootDir + "done.txt", "UTF-8");
				for (String filePath : processedFiles) {
					writer.println(filePath);
				}
				for (String filePath : messageFiles) {
					writer.println(filePath);
				}
				writer.close();
				System.out.println("done.txt written successfully...");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("Nothing new to process...");
		}
	}


	private static ArrayList<String> getProcessedFiles(File processedFileRecord, ArrayList<String> processedFiles) {
		try {
			FileReader fr = new FileReader(processedFileRecord);
			BufferedReader br = new BufferedReader(fr); 
			String filePath; 
			while((filePath = br.readLine()) != null) { 
				processedFiles.add(filePath);
			} 
			br.close();
			fr.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return processedFiles;
	}

	private static ArrayList<String> getDirectoryContents(File dir, ArrayList<String> processedFiles, ArrayList<String> messageFiles) {
		try {
			File[] files = dir.listFiles();
			for (File file : files) {
				if (file.isDirectory()) {
					messageFiles = getDirectoryContents(file, processedFiles, messageFiles);
				} else {
					if((file.getCanonicalPath().endsWith(".txt")) || (file.getCanonicalPath().endsWith(".msg")))
						if(!(processedFiles.contains(file.getCanonicalPath())))
							messageFiles.add(file.getCanonicalPath());
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return messageFiles;
	}


	/**
	 * creates an {@link HSSFWorkbook} the specified OS filename.
	 */
	private static XSSFWorkbook readFile(String filename) throws IOException {
		return new XSSFWorkbook(new FileInputStream(filename));
	}

	/**
	 * @return the configFilename
	 */
	public String getConfigFilename() {
		return configFilename;
	}


	/**
	 * @return the openTSDBConfiguration
	 */
	public OpenTSDBConfiguration getOpenTSDBConfiguration() {
		return openTSDBConfiguration;
	}


	/**
	 * @param openTSDBConfiguration the openTSDBConfiguration to set
	 */
	public void setOpenTSDBConfiguration(OpenTSDBConfiguration openTSDBConfiguration) {
		this.openTSDBConfiguration = openTSDBConfiguration;
	}

}