package org.msbel;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    private static final Logger logger = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        Path karateFolder = Paths.get("src/main/resources/karate");
        Path generatedFolder = Paths.get("src/main/resources/generated");

        logger.info("Starting the process of converting Karate feature files to cURL commands.");

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(karateFolder, "*.feature")) {
            for (Path karateFile : directoryStream) {
                logger.info("Processing file: " + karateFile.toString());

                String karateContent = new String(Files.readAllBytes(karateFile));
                List<String> curlCommands = parseKarateFeatureToCurl(karateContent);

                Path generatedFile = generatedFolder.resolve(karateFile.getFileName().toString().replace(".feature", ".txt"));

                try (BufferedWriter writer = Files.newBufferedWriter(generatedFile)) {
                    logger.info("Generating cURL commands for: " + generatedFile.toString());

                    for (String curlCommand : curlCommands) {
                        writer.write(curlCommand);
                        writer.newLine();
                        writer.newLine();  // Separate cURL commands with an empty line
                    }

                    logger.info("cURL commands have been saved to " + generatedFile);
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Error writing to file: " + generatedFile.toString(), e);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading from Karate folder: " + karateFolder.toString(), e);
        }

        logger.info("Process completed.");
    }

    public static List<String> parseKarateFeatureToCurl(String karateContent) {
        List<String> curlCommands = new ArrayList<>();
        String[] lines = karateContent.split("\n");
        String baseUrl = "";
        String path = "";
        String method = "";
        Map<String, String> headers = new HashMap<>();
        String body = "";
        Map<String, String> params = new HashMap<>();
        Map<String, String> variables = new HashMap<>();
        boolean newScenario = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Replace variables in the line
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                line = line.replace(entry.getKey(), entry.getValue());
            }

            if (line.startsWith("Scenario:") || line.startsWith("@")) {
                newScenario = true;
                method = "";  // Reset the method for each new scenario
                headers.clear();  // Clear headers for each new scenario
                body = "";  // Clear body for each new scenario
                path = "";  // Clear path for each new scenario
                params.clear();  // Clear params for each new scenario
                continue;
            }

            if (newScenario && line.startsWith("And") && !method.isEmpty()) {
                // If a new scenario starts with "And" and a method has already been defined, reset the method and headers
                method = "";
                headers.clear();
                body = "";
                path = "";
                params.clear();
            }

            if (line.startsWith("* def")) {
                String[] parts = line.split("=");
                String variableName = parts[0].split(" ")[2].trim();
                String variableValue = parts.length > 1 ? parts[1].trim() : "";
                if (variableValue.isEmpty() || variableValue.endsWith("=")) {
                    i++;
                    variableValue = lines[i].trim();
                }
                if (variableValue.startsWith("\"\"\"") || variableValue.startsWith("'''")) {
                    variableValue = extractJsonBody(lines, i);
                    i += variableValue.split("\n").length + 1;
                }
                variables.put(variableName, variableValue.replaceAll("'", "").replaceAll("\"", ""));
            }

            if (line.startsWith("And url") || line.startsWith("* url")) {
                baseUrl = extractValueBetweenQuotes(line);
                newScenario = false;  // Reset the newScenario flag after processing the URL
            } else if (line.startsWith("And path") || line.startsWith("* path")) {
                path = extractValueBetweenQuotes(line);
            } else if (line.startsWith("And header") || line.startsWith("* header")) {
                String[] headerParts = line.split("=");
                String headerName = headerParts[0].split(" ")[2].trim();
                String headerValue = headerParts[1].trim().replaceAll("'", "").replaceAll("\"", "");
                headers.put(headerName, headerValue);
            } else if (line.startsWith("When method") || line.startsWith("* method")) {
                method = line.split(" ")[2].toUpperCase();
                headers.putIfAbsent("Accept", "application/json");
                if ((method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) && !headers.containsKey("Content-Type")) {
                    headers.put("Content-Type", "application/json");
                }
            } else if (line.startsWith("And param") || line.startsWith("* param")) {
                String[] paramParts = line.split("=");
                String paramName = paramParts[0].split(" ")[2].trim();
                String paramValue = paramParts[1].trim().replaceAll("'", "").replaceAll("\"", "");
                params.put(paramName, paramValue);
            } else if (line.startsWith("And request") || line.startsWith("* request")) {
                String variableName = line.split(" ")[2].trim();
                body = variables.get(variableName);
            }

            if (!method.isEmpty() && !baseUrl.isEmpty()) {
                StringBuilder curlCommand = new StringBuilder("curl -X ").append(method);

                headers.forEach((key, value) ->
                        curlCommand.append(String.format(" -H '%s: %s'", key, value)));

                curlCommand.append(String.format(" '%s%s", baseUrl, path));
                if (!params.isEmpty()) {
                    curlCommand.append("?");
                    params.forEach((key, value) ->
                            curlCommand.append(String.format("%s=%s&", key, value)));
                    curlCommand.setLength(curlCommand.length() - 1);
                }
                curlCommand.append("'");

                if (body != null && !body.isEmpty() && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                    curlCommand.append(String.format(" -d '%s'", body.replace("\n", "").replace("'", "\\'")));
                }

                curlCommands.add(curlCommand.toString());

                method = "";
                headers.clear();
                body = "";
                path = "";
                params.clear();
            }
        }

        return curlCommands;
    }

    private static String extractValueBetweenQuotes(String line) {
        int startIndex = line.indexOf("'") != -1 ? line.indexOf("'") : line.indexOf("\"");
        int endIndex = line.lastIndexOf("'") != -1 ? line.lastIndexOf("'") : line.lastIndexOf("\"");
        return startIndex >= 0 && endIndex > startIndex ? line.substring(startIndex + 1, endIndex) : "";
    }

    private static String extractJsonBody(String[] lines, int startIndex) {
        StringBuilder jsonBody = new StringBuilder();
        for (int i = startIndex + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.endsWith("\"\"\"") || line.endsWith("'''")) {
                break;
            }
            jsonBody.append(line).append("\n");
        }
        return jsonBody.toString().trim();
    }
}
