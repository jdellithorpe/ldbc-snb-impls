/* 
 * Copyright (C) 2016 Stanford University
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.ellitron.ldbcsnbimpls.interactive.neo4j.util;

import org.docopt.Docopt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * A utility for converting dataset files generated by the LDBC SNB Data
 * Generator[1] to the file format expected by the Neo4j import tool[2].
 * <p>
 * TODO:<br>
 * <ul>
 * <li>Add specific instructions.</li>
 * <li>More flexible file searching for multipart files.</li>
 * </ul>
 * <p>
 * [1]: git@github.com:ldbc/ldbc_snb_datagen.git<br>
 * [2]: http://neo4j.com/docs/stable/import-tool.html<br>
 *
 * @author Jonathan Ellithorpe (jde@cs.stanford.edu)
 */
public class DataFormatConverter {

  private static final String doc =
      "DataFormatConverter: A utility for converting dataset files generated "
      + "by the LDBC SNB Data Generator to the file format expected by the "
      + "Neo4j import tool. Output files are placed in the DEST directory, "
      + "along with an import.sh script that runs the Neo4j Import Tool "
      + "automatically on those files. For this script to work, please set "
      + "your NEO4J_HOME environment variable appropriately."
      + "\n"
      + "Usage:\n"
      + "  DataFormatConverter SOURCE DEST\n"
      + "  DataFormatConverter (-h | --help)\n"
      + "  DataFormatConverter --version\n"
      + "\n"
      + "Arguments:\n"
      + "  SOURCE  Directory containing SNB dataset files.\n"
      + "  DEST    Destination directory for output files.\n"
      + "\n"
      + "Options:\n"
      + "  -h --help         Show this screen.\n"
      + "  --version         Show version.\n"
      + "\n";

  /**
   * Represents all the types of nodes in the graph and their various
   * properties.
   */
  private enum Node {

    COMMENT("COMMENT_ID", "id", "Message;Comment", "comment",
        new String[]{"creationDate", "locationIP", "browserUsed", "content",
          "length"}),
    FORUM("FORUM_ID", "id", "Forum", "forum",
        new String[]{"title", "creationDate"}),
    ORGANISATION("ORGANISATION_ID", "id", "Organisation", "organisation",
        new String[]{"type", "name", "url"}),
    PERSON("PERSON_ID", "id", "Person", "person",
        new String[]{"firstName", "lastName", "gender", "birthday",
          "creationDate", "locationIP", "browserUsed", "email", "speaks"}),
    PLACE("PLACE_ID", "id", "Place", "place",
        new String[]{"name", "url", "type"}),
    POST("POST_ID", "id", "Message;Post", "post",
        new String[]{"imageFile", "creationDate", "locationIP", "browserUsed",
          "language", "content", "length"}),
    TAG("TAG_ID", "id", "Tag", "tag",
        new String[]{"name", "url"}),
    TAGCLASS("TAGCLASS_ID", "id", "TagClass", "tagclass",
        new String[]{"name", "url"});

    /*
     * "ID space" for the node (see Neo4j Import Tool documentation). IDs
     * within an ID space must be unique. Note that Comments and Posts both
     * belong to the ID space for messages. This is because their IDs are
     * unique together in this space, and the benchmark sometimes requires
     * quering a "message" with a specific ID, which could be either a post or
     * a comment.
     */
    private final String neoIdSpace;

    /*
     * The property key used to refer to the id in Cypher queries.
     */
    private final String neoIdPropKey;

    /*
     * The label given to these nodes. We use the same labels here as used in
     * the Cypher queries for Neo4j listed in the appendix of the LDBC SNB
     * specification v0.2.2.
     */
    private final String neoLabel;

    /*
     * This is the name used in fileNames for the node as output by the LDBC
     * SNB Data Generator.
     */
    private final String fileTag;

    /*
     * Ordered array of the properties for this node, in the order they appear
     * in the LDBC SNB Data Generator dataset files.
     */
    private final String[] props;

    private Node(String neoIdSpace, String neoIdPropKey,
        String neoLabel, String fileTag, String[] props) {
      this.neoIdSpace = neoIdSpace;
      this.neoIdPropKey = neoIdPropKey;
      this.neoLabel = neoLabel;
      this.fileTag = fileTag;
      this.props = props;
    }

    public String getNeoIdSpace() {
      return neoIdSpace;
    }

    public String getNeoIdPropKey() {
      return neoIdPropKey;
    }

    public String getNeoLabel() {
      return neoLabel;
    }

    public String getFileTag() {
      return fileTag;
    }

    public String[] getProps() {
      return props;
    }
  }

  /**
   * Represents all the types of relationships in the graph. Each relationship
   * has a "type" which is used to refer to relationships of that type in
   * Cypher queries. This "type" is overloaded in the sense that it is also
   * part of the filenames that contain relationships of this type (e.g.
   * person_isLocatedIn_place_0_0.csv).
   */
  private enum Relationship {

    CONTAINEROF("CONTAINER_OF", "containerOf", new String[]{}),
    HASCREATOR("HAS_CREATOR", "hasCreator", new String[]{}),
    HASINTEREST("HAS_INTEREST", "hasInterest", new String[]{}),
    HASMEMBER("HAS_MEMBER", "hasMember", new String[]{"joinDate"}),
    HASMODERATOR("HAS_MODERATOR", "hasModerator", new String[]{}),
    HASTAG("HAS_TAG", "hasTag", new String[]{}),
    HASTYPE("HAS_TYPE", "hasType", new String[]{}),
    ISLOCATEDIN("IS_LOCATED_IN", "isLocatedIn", new String[]{}),
    ISPARTOF("IS_PART_OF", "isPartOf", new String[]{}),
    ISSUBCLASSOF("IS_SUBCLASS_OF", "isSubclassOf", new String[]{}),
    KNOWS("KNOWS", "knows", new String[]{"creationDate"}),
    LIKES("LIKES", "likes", new String[]{"creationDate"}),
    REPLYOF("REPLY_OF", "replyOf", new String[]{}),
    SPEAKS("SPEAKS", "speaks", new String[]{}),
    STUDYAT("STUDY_AT", "studyAt", new String[]{"classYear"}),
    WORKAT("WORK_AT", "workAt", new String[]{"workFrom"});

    /*
     * The type given to these nodes in Neo4j. We use the same types here as
     * used in the Cypher queries for Neo4j listed in the appendix of the LDBC
     * SNB specification v0.2.2.
     */
    private final String neoType;

    /*
     * The named used in LDBC SNB Data Generator output files referring to this
     * type of relationship.
     */
    private final String fileTag;

    /*
     * Ordered array of the properties for this relationship type, in the order
     * they appear in the LDBC SNB Data Generator dataset files.
     */
    private final String[] props;

    private Relationship(String neoType, String fileTag, String[] props) {
      this.neoType = neoType;
      this.fileTag = fileTag;
      this.props = props;
    }

    public String getNeoType() {
      return neoType;
    }

    public String getFileTag() {
      return fileTag;
    }

    public String[] getProps() {
      return props;
    }
  }

  /*
   * Stores a fixed mapping between property names and their data types in the
   * Neo4j schema.
   */
  private static final Map<String, String> propDataTypes;

  /*
   * Used for parsing dates in the original dataset files output by the data
   * generator, and converting them to milliseconds since Jan. 1 9170. We store
   * dates in this form in Neo4j.
   */
  private static final SimpleDateFormat birthdayDateFormat;
  private static final SimpleDateFormat creationDateDateFormat;

  static {
    Map<String, String> dataTypeMap = new HashMap<>();
    dataTypeMap.put("birthday", "long");
    dataTypeMap.put("browserUsed", "string");
    dataTypeMap.put("classYear", "int");
    dataTypeMap.put("content", "string");
    dataTypeMap.put("creationDate", "long");
    dataTypeMap.put("email", "string[]");
    dataTypeMap.put("firstName", "string");
    dataTypeMap.put("gender", "string");
    dataTypeMap.put("imageFile", "string");
    dataTypeMap.put("joinDate", "long");
    dataTypeMap.put("language", "string");
    dataTypeMap.put("lastName", "string");
    dataTypeMap.put("length", "int");
    dataTypeMap.put("locationIP", "string");
    dataTypeMap.put("name", "string");
    dataTypeMap.put("speaks", "string[]");
    dataTypeMap.put("title", "string");
    dataTypeMap.put("type", "string");
    dataTypeMap.put("url", "string");
    dataTypeMap.put("workFrom", "int");

    propDataTypes = Collections.unmodifiableMap(dataTypeMap);

    birthdayDateFormat =
        new SimpleDateFormat("yyyy-MM-dd");
    birthdayDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    creationDateDateFormat =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    creationDateDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  /**
   * Parse a property file to coalesce multiple properties for a single id into
   * a list of those properties for the id. Used for parsing the email and
   * speaks property files for person nodes, but can be used on any node
   * property file with the same format.
   *
   * @param path Path to the property file.
   *
   * @return Map from node id to List of property values for the property
   * represented by the parsed file.
   *
   * @throws IOException
   */
  private static Map<String, List<String>> parsePropFile(List<Path> paths)
      throws IOException {
    Map<String, List<String>> propMap = new HashMap<>();

    for (Path path : paths) {
      BufferedReader propFile =
          Files.newBufferedReader(path, StandardCharsets.UTF_8);

      String line;
      propFile.readLine(); // Skip over the first line (column headers).
      while ((line = propFile.readLine()) != null) {
        String[] lineParts = line.split("\\|");
        String id = lineParts[0];
        String prop = lineParts[1];
        if (propMap.containsKey(id)) {
          propMap.get(id).add(prop);
        } else {
          List<String> list = new ArrayList<>();
          list.add(prop);
          propMap.put(id, list);
        }
      }
      propFile.close();
    }

    return propMap;
  }

  /**
   * Serialize a list of property values into a single String formatted for the
   * Neo4j Import Tool.
   *
   * @param propList List of property values.
   *
   * @return Serialized string of property values.
   */
  private static String serializePropertyValueList(List<String> propList) {
    StringBuilder sb = new StringBuilder();
    sb.append("\"");
    for (int i = 0; i < propList.size(); i++) {
      // If not first element, start with array separator
      if (i > 0) {
        sb.append(";");
      }

      sb.append(propList.get(i));
    }
    sb.append("\"");

    return sb.toString();
  }

  public static void main(String[] args)
      throws FileNotFoundException, IOException, ParseException {
    Map<String, Object> opts =
        new Docopt(doc).withVersion("DataFormatConverter 1.0").parse(args);

    /*
     * Save the output file names. We'll use this later to output a script
     * containing the full neo4j-import command to import all the files
     * converted by this utility.
     */
    List<String> outputNodeFiles = new ArrayList<>();
    List<String> outputRelFiles = new ArrayList<>();

    String inputDir = (String) opts.get("SOURCE");
    String outputDir = (String) opts.get("DEST");

    System.out.println(String.format("Processing person properties..."));

    // Prepare email and speaks properties to be added to Person nodes.
    File allFiles[] = (new File(inputDir)).listFiles();
    List<Path> emailaddressPropFilePaths = new ArrayList<>();
    List<Path> languagePropFilePaths = new ArrayList<>();
    for (int i = 0; i < allFiles.length; i++) {
      if (allFiles[i].isFile()) {
        if (allFiles[i].getName().matches(Node.PERSON.getFileTag() + "_email_emailaddress_\\d+_0.csv"))
          emailaddressPropFilePaths.add(allFiles[i].toPath());
        else if (allFiles[i].getName().matches(Node.PERSON.getFileTag() + "_speaks_language_\\d+_0.csv"))
          languagePropFilePaths.add(allFiles[i].toPath());
      }
    }

    Map<String, List<String>> personEmail = parsePropFile(emailaddressPropFilePaths);

    Map<String, List<String>> personSpeaks = parsePropFile(languagePropFilePaths);

    /*
     * Nodes.
     */
    for (Node node : Node.values()) {
      List<String> fileNames = new ArrayList<>();
      for (int i = 0; i < allFiles.length; i++) {
        if (allFiles[i].isFile()) {
          if (allFiles[i].getName().matches(node.getFileTag() + "_\\d+_0.csv"))
            fileNames.add(allFiles[i].getName());
        }
      }

      System.out.println(String.format("Processing %s nodes (%d files)...", 
          node.getFileTag(), fileNames.size()));

      for (String fileName : fileNames) {
        Path path = Paths.get(inputDir + "/" + fileName);
        BufferedReader inFile =
            Files.newBufferedReader(path, StandardCharsets.UTF_8);

        path = Paths.get(outputDir + "/" + fileName);
        BufferedWriter outFile =
            Files.newBufferedWriter(path, StandardCharsets.UTF_8);

        outputNodeFiles.add(fileName);

        /*
        * Replace the headers (first line) with those expected by the Neo4j
        * Import Tool.
        */
        // Skip over header line of input file.
        inFile.readLine();

        // First field is always the ID.
        outFile.append(String.format(
            "%s:ID(%s)", node.getNeoIdPropKey(), node.getNeoIdSpace()));

        // Then the properties.
        List<String> nodeProps = Arrays.asList(node.getProps());
        for (String property : nodeProps) {
          outFile.append("|" + property + ":" + propDataTypes.get(property));

          if (property.equals("birthday")) {
            outFile.append("|birthday_day:int");
            outFile.append("|birthday_month:int");
          }
        }

        // And the last field is always a label for this node type.
        outFile.append("|:LABEL\n");

        /*
        * Now go through every line of the file processing certain columns,
        * adding fields, and adding labels as necessary.
        */
        String line;
        while ((line = inFile.readLine()) != null) {
          /*
          * Date-type fields (birthday, creationDate, ...) need to be converted
          * to the number of milliseconds since January 1, 1970, 00:00:00 GMT.
          * This is the format expected to be returned for these fields by LDBC
          * SNB benchmark queries, although the format in the dataset files are
          * things like "1989-12-04" and "2010-03-17T23:32:10.447+0000". We
          * could do this conversion "live" during the benchmark, but that would
          * detract from the performance numbers' reflection of true database
          * performance since it would add to the client-side query processing
          * overhead.
          */
          String[] colVals = line.split("\\|");
          for (int i = 0; i < colVals.length; i++) {
            if (i > 0) {
              if (nodeProps.get(i - 1).equals("birthday")) {
                Date birthday = birthdayDateFormat.parse(colVals[i]);
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
                cal.setTime(birthday);
                outFile.append(
                    String.valueOf(cal.getTimeInMillis()) + "|");
                outFile.append(
                    String.valueOf(cal.get(Calendar.DAY_OF_MONTH)) + "|");
                outFile.append(
                    String.valueOf(cal.get(Calendar.MONTH) + 1) + "|");
              } else if (nodeProps.get(i - 1).equals("creationDate")) {
                outFile.append(String.valueOf(
                    creationDateDateFormat.parse(colVals[i]).getTime()) + "|");
              } else if (propDataTypes.get(nodeProps.get(i - 1)).equals("string") && colVals[i].length() > 0) {
                outFile.append("\"" + colVals[i] + "\"" + "|");
              } else {
                outFile.append(colVals[i] + "|");
              }
            } else {
              outFile.append(colVals[i] + "|");
            }
          }

          /*
          * For person nodes we merge their email and speaks properties listed
          * in their respective property files into the person node file. This
          * is because the Neo4j Import Tool does not have a way to import
          * properties separate from those listed in node and relationship
          * files.
          */
          if (node.equals(Node.PERSON)) {
            String id = colVals[0];

            // First append emails.
            if (personEmail.containsKey(id)) {
              String email = serializePropertyValueList(personEmail.get(id));
              outFile.append(email);
            }
            outFile.append("|");

            // Then append languages this person speaks.
            if (personSpeaks.containsKey(id)) {
              String speaks = serializePropertyValueList(personSpeaks.get(id));
              outFile.append(speaks);
            }
            outFile.append("|");
          }

          // Append the node's label to the end of the line.
          outFile.append(node.getNeoLabel() + "\n");
        }

        inFile.close();
        outFile.close();
      }
    }

    /*
     * Relationships.
     */
    for (Node srcNode : Node.values()) {
      for (Relationship rel : Relationship.values()) {
        for (Node dstNode : Node.values()) {
          List<String> fileNames = new ArrayList<>();
          for (int i = 0; i < allFiles.length; i++) {
            if (allFiles[i].isFile()) {
              if (allFiles[i].getName().matches(srcNode.getFileTag() 
                    + "_" + rel.getFileTag() 
                    + "_" + dstNode.getFileTag() 
                    + "_\\d+_0.csv"))
                fileNames.add(allFiles[i].getName());
            }
          }

          if (fileNames.size() == 0) {
            // No relationships of this type.
            continue;
          }

          System.out.println(String.format(
              "Processing (%s)-[%s]->(%s) relationships (%d files)...",
              srcNode.getFileTag(),
              rel.getFileTag(),
              dstNode.getFileTag(),
              fileNames.size()));

          for (String fileName : fileNames) {
            Path path = Paths.get(inputDir + "/" + fileName);

            BufferedReader inFile = Files.newBufferedReader(path, StandardCharsets.UTF_8);

            outputRelFiles.add(fileName);

            path = Paths.get(outputDir + "/" + fileName);
            BufferedWriter outFile =
                Files.newBufferedWriter(path, StandardCharsets.UTF_8);

            /*
            * Replace the headers (first line) with those expected by the Neo4j
            * Import Tool.
            */
            // Skip over header line of input file.
            inFile.readLine();

            // First two fields are always the src/dst ID.
            outFile.append(String.format(
                ":START_ID(%s)", srcNode.getNeoIdSpace()));
            outFile.append(String.format(
                "|:END_ID(%s)", dstNode.getNeoIdSpace()));

            // Then the properties.
            List<String> relProps = Arrays.asList(rel.getProps());
            for (String property : relProps) {
              outFile.append("|" + property + ":" + propDataTypes.get(property));
            }

            // And the last field is always a type for this relationship type.
            outFile.append("|:TYPE\n");

            /*
            * Now go through every line of the file processing certain columns
            * and adding types as necessary.
            */
            String line;
            while ((line = inFile.readLine()) != null) {
              /*
              * Date-type fields (creationDate, joinDate...) need to be
              * converted to the number of milliseconds since January 1, 1970,
              * 00:00:00 GMT. This is the format expected to be returned for
              * these fields by LDBC SNB benchmark queries, although the format
              * in the dataset files are things like
              * "2010-03-17T23:32:10.447+0000". We could do this conversion
              * "live" during the benchmark, but that would detract from the
              * performance numbers' reflection of true database performance
              * since it would add to the client-side query processing overhead.
              */
              String[] colVals = line.split("\\|");
              for (int i = 0; i < colVals.length; i++) {
                if (i > 1) {
                  if (relProps.get(i - 2).equals("creationDate")) {
                    outFile.append(String.valueOf(
                        creationDateDateFormat.parse(colVals[i]).getTime())
                        + "|");
                  } else if (relProps.get(i - 2).equals("joinDate")) {
                    outFile.append(String.valueOf(
                        creationDateDateFormat.parse(colVals[i]).getTime())
                        + "|");
                  } else if (propDataTypes.get(relProps.get(i - 2)).equals("string") && colVals[i].length() > 0) {
                    outFile.append("\"" + colVals[i] + "\"" + "|");
                  } else {
                    outFile.append(colVals[i] + "|");
                  }
                } else {
                  outFile.append(colVals[i] + "|");
                }
              }

              // Append the relationship's type to the end of the line.
              outFile.append(rel.getNeoType() + "\n");
            }

            inFile.close();
            outFile.close();
          }
        }
      }
    }

    /*
     * Now we'll output a script the user can use to run the neo4j import tool
     * on all the files we just created.
     */
    Path path = Paths.get(outputDir + "/import.sh");
    BufferedWriter outFile =
        Files.newBufferedWriter(path, StandardCharsets.UTF_8);

    outFile.append("#!/bin/sh\n");
    outFile.append("$NEO4J_HOME/bin/neo4j-admin import --database graph.db");

    for (String nodeFile : outputNodeFiles) {
      outFile.append(" --nodes " + nodeFile);
    }

    for (String relFile : outputRelFiles) {
      outFile.append(" --relationships " + relFile);
    }

    outFile.append(" --delimiter \"|\" --array-delimiter \";\"\n");

    outFile.close();
  }
}
