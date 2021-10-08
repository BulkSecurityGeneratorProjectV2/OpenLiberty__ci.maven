/**
 * (C) Copyright IBM Corporation 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.openliberty.tools.maven.server;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.xml.sax.SAXException;

import io.openliberty.tools.common.plugins.config.ServerConfigXmlDocument;
import io.openliberty.tools.common.plugins.util.InstallFeatureUtil;
import io.openliberty.tools.common.plugins.util.InstallFeatureUtil.ProductProperties;
import io.openliberty.tools.common.plugins.util.PluginExecutionException;
import io.openliberty.tools.common.plugins.util.PluginScenarioException;
import io.openliberty.tools.maven.BasicSupport;
import io.openliberty.tools.maven.InstallFeatureSupport;

/**
 * This mojo generates the features required in the featureManager element in server.xml.
 * It examines the dependencies declared in the pom.xml and the features already declared
 * in the featureManager elements in the XML configuration files. Then it generates any
 * missing feature names and stores them in a new featureManager element in a new XML file.
 */
@Mojo(name = "generate-features")
public class GenerateFeaturesMojo extends InstallFeatureSupport {

    /**
     * The name of the jar file which contains the binary scanner used to detect features.
     */
    @Parameter(property = "featureScannerJar")
    private File binaryScanner;

    protected static final String PLUGIN_ADDED_FEATURES_FILE = "configDropins/overrides/liberty-plugin-added-features.xml";
    protected static final String FEATURES_FILE_MESSAGE = "The Liberty Maven Plugin has generated Liberty features necessary for your application in " + PLUGIN_ADDED_FEATURES_FILE;
    protected static final String HEADER = "# Generated by liberty-maven-plugin";

    /*
     * (non-Javadoc)
     * @see org.codehaus.mojo.pluginsupport.MojoSupport#doExecute()
     */
    @Override
    protected void doExecute() throws Exception {
        if(!initialize()) {
            return;
        }
        generateFeatures();
    }

    private void generateFeatures() throws PluginExecutionException {
        List<ProductProperties> propertiesList = InstallFeatureUtil.loadProperties(installDirectory);
        String openLibertyVersion = InstallFeatureUtil.getOpenLibertyVersion(propertiesList);

        InstallFeatureUtil util;
        try {
            util = new InstallFeatureMojoUtil(new HashSet<String>(), propertiesList, openLibertyVersion, null);
        } catch (PluginScenarioException e) {
            log.debug("Exception creating the server utility object", e);
            log.error("Error attempting to generate server feature list.");
            return;
        }

        util.setLowerCaseFeatures(false); // this is our own instance and should not affect others.
        Set<String> visibleServerFeatures = util.getAllServerFeatures();

        Set<String> libertyFeatureDependencies = getFeaturesFromDependencies(project);
        log.debug("maven dependencies that are liberty features:"+libertyFeatureDependencies);

        // Remove project dependency features which are hidden.
        Set<String> visibleLibertyProjectDependencies = new HashSet<String>(libertyFeatureDependencies);
        visibleLibertyProjectDependencies.retainAll(visibleServerFeatures);
        log.debug("maven dependencies that are VALID liberty features:"+visibleLibertyProjectDependencies);

        File newServerXmlSrc = new File(configDirectory, PLUGIN_ADDED_FEATURES_FILE);
        File newServerXmlTarget = new File(serverDirectory, PLUGIN_ADDED_FEATURES_FILE);
        File serverXml = findConfigFile("server.xml", serverXmlFile);
        ServerConfigXmlDocument doc = getServerXmlDocFromConfig(serverXml);
        log.debug("Xml document we'll try to update after generate features doc="+doc+" file="+serverXml);

        Map<String, File> libertyDirPropertyFiles;
        try {
            if (newServerXmlTarget.exists()) {  // about to regenerate this file. Must be removed before getLibertyDirectoryPropertyFiles
                newServerXmlTarget.delete();
                removeGenerationCommentFromConfig(doc, serverXml); // remove reference to file just deleted.
            }
            libertyDirPropertyFiles = BasicSupport.getLibertyDirectoryPropertyFiles(installDirectory, userDirectory, serverDirectory);
        } catch (IOException e) {
            if (!newServerXmlTarget.exists()) { // restore the xml file just deleted
                if (newServerXmlSrc.exists()) {
                    try {
                        Files.copy(newServerXmlSrc.toPath(), newServerXmlTarget.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        addGenerationCommentToConfig(doc, serverXml);
                    } catch (IOException f) {
                        log.debug("Exception trying to restore file: "+PLUGIN_ADDED_FEATURES_FILE+". "+f);
                    }
                }
            }
            log.debug("Exception reading the server property files", e);
            log.error("Error attempting to generate server feature list. Ensure your user account has read permission to the property files in the server installation directory.");
            return;
        }
        // TODO: get user specified features that have not yet been installed in the
        // original case they appear in a server config xml document.
        // getSpecifiedFeatures may not return the features in the correct case
        // Set<String> featuresToInstall = getSpecifiedFeatures(null); 

        // get existing installed server features
        Set<String> existingFeatures = util.getServerFeatures(serverDirectory, libertyDirPropertyFiles);
        if (existingFeatures == null) {
            existingFeatures = new HashSet<String>();
        }
        log.debug("Existing features:" + existingFeatures);

        // The Liberty features missing from server.xml
        Set<String> missingLibertyFeatures = getMissingLibertyFeatures(visibleLibertyProjectDependencies,
				existingFeatures);
        log.debug("maven dependencies that are not hidden liberty features but are missing from server.xml:"+missingLibertyFeatures);

        // Scan for features after processing the POM. POM features take priority over scannned features
        Set<String> scannedFeatureList = runBinaryScanner(existingFeatures);
        if (scannedFeatureList != null) {
            // tabulate the existing features by name and version number and lookup each scanned feature
            Map<String, String> existingFeatureMap = new HashMap();
            for (String existingFeature : existingFeatures) {
                String[] nameAndVersion = getNameAndVersion(existingFeature);
                existingFeatureMap.put(nameAndVersion[0], nameAndVersion[1]);
            }
            for (String missingLibertyFeature : missingLibertyFeatures) {
                String[] nameAndVersion = getNameAndVersion(missingLibertyFeature);
                existingFeatureMap.put(nameAndVersion[0], nameAndVersion[1]);
            }
            for (String scannedFeature : scannedFeatureList) {
                String[] scannedNameAndVersion = getNameAndVersion(scannedFeature);
                String existingFeatureVersion = existingFeatureMap.get(scannedNameAndVersion[0]);
                if (existingFeatureVersion != null) {
                    if (existingFeatureVersion.compareTo(scannedNameAndVersion[1]) < 0) {
                        log.warn(String.format("The binary scanner detected a dependency on %s but the project's POM or server.xml specified the dependency %s-%s.", scannedFeature, scannedNameAndVersion[0], existingFeatureVersion));
                    }
                } else {
                    // scanned feature not found in server.xml or POM
                    missingLibertyFeatures.add(scannedFeature);
                    log.debug(String.format("Adding feature %s to server.xml because it was detected by binary scanner.", scannedFeature));
                }
            }
        }
        if (missingLibertyFeatures.size() > 0) {
            // Create specialized server.xml
            try {
                ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                configDocument.createComment(HEADER);
                for (String missing : missingLibertyFeatures) {
                    log.debug(String.format("Adding missing feature %s to %s.", missing, PLUGIN_ADDED_FEATURES_FILE));
                    configDocument.createFeature(missing);
                }
                configDocument.writeXMLDocument(newServerXmlSrc);
                Files.copy(newServerXmlSrc.toPath(), newServerXmlTarget.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("Created file "+newServerXmlSrc);
                // Add a reference to this new file in existing server.xml.
                addGenerationCommentToConfig(doc, serverXml);
            } catch(ParserConfigurationException | TransformerException | IOException e) {
                log.debug("Exception creating the server features file", e);
                log.error("Error attempting to create the server feature file. Ensure your id has write permission to the server installation directory.");
                return;
            }
        }
    }

    /**
     * Comb through the list of Maven project dependencies and find the ones which are 
     * Liberty features.
     * @param project  Current Maven project
     * @return List of names of dependencies
     */
    private Set<String> getFeaturesFromDependencies(MavenProject project) {
        Set<String> libertyFeatureDependencies = new HashSet<String>();
        List<Dependency> allProjectDependencies = project.getDependencies();
        for (Dependency d : allProjectDependencies) {
            String featureName = getFeatureName(d);
            if (featureName != null) {
                libertyFeatureDependencies.add(featureName);
            }
        }
        return libertyFeatureDependencies;
    }

    /**
     * From all the candidate project dependencies remove the ones already in server.xml
     * to make the list of the ones that are missing from server.xml.
     * @param visibleLibertyProjectDependencies
     * @param existingFeatures
     * @return
     */
    private Set<String> getMissingLibertyFeatures(Set<String> visibleLibertyProjectDependencies,
            Set<String> existingFeatures) {
        Set<String> missingLibertyFeatures = new HashSet<String>(visibleLibertyProjectDependencies);
        if (existingFeatures != null) {
            for (String s : visibleLibertyProjectDependencies) {
                // existingFeatures mixed case has been preserved.
                if (existingFeatures.contains(s)) {
                    missingLibertyFeatures.remove(s);
                }
            }
        }
        return missingLibertyFeatures;
    }

	/**
	 * Determine if a dependency is a Liberty feature or not
	 * @param mavenDependency  a Maven project dependency 
	 * @return the Liberty feature name if the input is a Liberty feature otherwise return null.
	 */
    private String getFeatureName(Dependency mavenDependency) {
        if ("esa".contentEquals(mavenDependency.getType())) {
            return mavenDependency.getArtifactId();
        }
        return null;
    }

    /*
     * Return specificFile if it exists; otherwise return the file with the requested fileName from the 
     * configDirectory, but only if it exists. Null is returned if the file does not exist in either location.
     */
    private File findConfigFile(String fileName, File specificFile) {
        if (specificFile != null && specificFile.exists()) {
            return specificFile;
        }

        File f = new File(configDirectory, fileName);
        if (configDirectory != null && f.exists()) {
            return f;
        }
        return null;
    }

    private ServerConfigXmlDocument getServerXmlDocFromConfig(File serverXml) {
        if (serverXml == null || !serverXml.exists()) {
            return null;
        }
        try {
            return ServerConfigXmlDocument.newInstance(serverXml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.debug("Exception creating server.xml object model", e);
        }
        return null;
    }

    /**
     * Remove the comment in server.xml that warns we created another file with features in it.
     */
    private void removeGenerationCommentFromConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            doc.removeFMComment(FEATURES_FILE_MESSAGE);
            doc.writeXMLDocument(serverXml);
        } catch (IOException | TransformerException e) {
            log.debug("Exception removing comment from server.xml", e);
        }
        return;
    }

    /**
     * Add a comment to server.xml to warn them we created another file with features in it.
     */
    private void addGenerationCommentToConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            doc.createFMComment(FEATURES_FILE_MESSAGE);
            doc.writeXMLDocument(serverXml);
        } catch (IOException | TransformerException e) {
            log.debug("Exception adding comment to server.xml", e);
        }
        return;
    }

    private Set<String> runBinaryScanner(Set<String> currentFeatureSet) {
        Set<String> featureList = null;
        if (binaryScanner != null && binaryScanner.exists()) {
            ClassLoader cl = this.getClass().getClassLoader();
            try {
                URLClassLoader ucl = new URLClassLoader(new URL[] { binaryScanner.toURI().toURL() }, cl);
                Class driveScan = ucl.loadClass("com.ibm.ws.report.binary.cmdline.DriveScan");
                // args: String[], String, String, List, java.util.Locale
                java.lang.reflect.Method driveScanMavenFeaureList = driveScan.getMethod("driveScanMavenFeaureList", String[].class, String.class, String.class, List.class, java.util.Locale.class);
                if (driveScanMavenFeaureList == null) {
                    log.debug("Error finding binary scanner method using reflection");
                    return null;
                }
                String[] directoryList = getClassesDirectories();
                if (directoryList == null || directoryList.length == 0) {
                    log.debug("Error collecting list of directories to send to binary scanner, list is null or empty.");
                    return null;
                }
                String eeVersion = getEEVersion(project); 
                String mpVersion = getMPVersion(project);
                List<String> currentFeatures = new ArrayList<String>(currentFeatureSet);
                log.debug("The following messages are from the application binary scanner used to generate Liberty features");
                featureList = (Set<String>) driveScanMavenFeaureList.invoke(null, directoryList, eeVersion, mpVersion, currentFeatures, java.util.Locale.getDefault());
                log.debug("End of messages from application binary scanner. Features recommended :");
                for (String s : featureList) {log.debug(s);};
            } catch (MalformedURLException|ClassNotFoundException|NoSuchMethodException|IllegalAccessException|java.lang.reflect.InvocationTargetException x){
                // TODO Figure out what to do when there is a problem scanning the features
                log.error("Exception:"+x.getClass().getName());
                Object o = x.getCause();
                if (o != null) {
                    log.warn("Caused by exception:"+x.getCause().getClass().getName());
                    log.warn("Caused by exception message:"+x.getCause().getMessage());
                }
                log.error(x.getMessage());
            }
        } else {
            log.debug("Unable to find the binary scanner jar");
        }
        return featureList;
    }

    // Return a list containing the classes directory of the current project and any upstream module projects
    private String[] getClassesDirectories() {
        List<String> dirs = new ArrayList();
        String classesDirName = null;
        // First check the Java build output directory (target/classes) for the current project
        classesDirName = getClassesDirectory(project.getBuild().getOutputDirectory());
        if (classesDirName != null) {
            dirs.add(classesDirName);
        }

        // Use graph to find upstream projects and look for classes directories. Some projects have no Java.
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        List<MavenProject> upstreamProjects = graph.getUpstreamProjects(project, true);
        log.debug("For binary scanner gathering Java build output directories for upstream projects, size=" + upstreamProjects.size());
        for (MavenProject upstreamProject : upstreamProjects) {
            classesDirName = getClassesDirectory(upstreamProject.getBuild().getOutputDirectory());
            if (classesDirName != null) {
                dirs.add(classesDirName);
            }
        }
        for (String s : dirs) {log.debug("Found dir:"+s);};
        return dirs.toArray(new String[dirs.size()]);
    }

    // Check one directory and if it exists return its canonical path (or absolute path if error).
    private String getClassesDirectory(String outputDir) {
        File classesDir = new File(outputDir);
        try {
            if (classesDir.exists()) {
                return classesDir.getCanonicalPath();
            }
        } catch (IOException x) {
            String classesDirAbsPath = classesDir.getAbsolutePath();
            log.debug("IOException obtaining canonical path name for a project's classes directory: " + classesDirAbsPath);
            return classesDirAbsPath;
        }
        return null; // directory does not exist.
    }

    public String getEEVersion(MavenProject project) {
        List<Dependency> dependencies = project.getDependencies();
        for (Dependency d : dependencies) {
            if (!d.getScope().equals("provided")) {
                continue;
            }
            log.debug("getEEVersion, dep="+d.getGroupId()+":"+d.getArtifactId()+":"+d.getVersion());
            if (d.getGroupId().equals("io.openliberty.features")) {
                String id = d.getArtifactId();
                if (id.equals("javaee-7.0")) {
                    return "ee7";
                } else if (id.equals("javaee-8.0")) {
                    return "ee8";
                } else if (id.equals("javaeeClient-7.0")) {
                    return "ee7";
                } else if (id.equals("javaeeClient-8.0")) {
                    return "ee8";
                } else if (id.equals("jakartaee-8.0")) {
                    return "ee8";
                }
            } else if (d.getGroupId().equals("jakarta.platform") &&
                    d.getArtifactId().equals("jakarta.jakartaee-api") &&
                    d.getVersion().equals("8.0.0")) {
                return "ee8";
            }
        }
        return null;
    }

    public String getMPVersion(MavenProject project) {  // figure out correct level of mp from declared dependencies
        List<Dependency> dependencies = project.getDependencies();
        int mpVersion = 0;
        for (Dependency d : dependencies) {
            if (!d.getScope().equals("provided")) {
                continue;
            }
            if (d.getGroupId().equals("org.eclipse.microprofile") &&
                d.getArtifactId().equals("microprofile")) {
                String version = d.getVersion();
                log.debug("dep=org.eclipse.microprofile:microprofile version="+version);
                if (version.startsWith("1")) {
                    return "mp1";
                } else if (version.startsWith("2")) {
                    return "mp2";
                } else if (version.startsWith("3")) {
                    return "mp3";
                }
                return "mp4"; // add support for future versions of MicroProfile here
            }
            if (d.getGroupId().equals("io.openliberty.features")) {
                mpVersion = Math.max(mpVersion, getMPVersion(d.getArtifactId()));
                log.debug("dep=io.openliberty.features:"+d.getArtifactId()+" mpVersion="+mpVersion);
            }
        }
        if (mpVersion == 1) {
            return "mp1";
        } else if (mpVersion == 2) {
            return "mp2";
        } else if (mpVersion == 3) {
            return "mp3";
        }
        return "mp4";
    }

    public static int getMPVersion(String shortName) {
        final int MP_VERSIONS = 4; // number of version columns in table
        String[][] mpComponents = {
            // Name, MP1 version, MP2 version, MP3 version
            { "mpconfig", "1.3", "1.3", "1.4", "2.0" },
            { "mpfaulttolerance", "1.1", "2.0", "2.1", "3.0" },
            { "mphealth", "1.0", "1.0", "2.2", "3.0" },
            { "mpjwt", "1.1", "1.1", "1.1", "1.2" },
            { "mpmetrics", "1.1", "1.1", "2.3", "3.0" },
            { "mpopenapi", "1.0", "1.1", "1.1", "2.0" },
            { "mpopentracing", "1.1", "1.3", "1.3", "2.0" },
            { "mprestclient", "1.1", "1.2", "1.4", "2.0" },
        };
        if (shortName == null) {
            return 0;
        }
        if (!shortName.startsWith("mp")) { // efficiency
            return 0;
        }
        String[] nameAndVersion = getNameAndVersion(shortName);
        if (nameAndVersion == null) {
            return 0;
        }
        String name = nameAndVersion[0];
        String version = nameAndVersion[1];
        for (int i = 0; i < mpComponents.length; i++) {
            if (mpComponents[i][0].equals(name)) {
                for (int j = MP_VERSIONS; j >= 0; j--) { // use highest compatible version
                    if (mpComponents[i][j].compareTo(version) < 0 ) {
                        return (j == MP_VERSIONS) ? MP_VERSIONS : j+1; // in case of error just return max version
                    }
                    if (mpComponents[i][j].compareTo(version) == 0 ) {
                        return j;
                    }
                }
                return 1; // version specified is between 1.0 and max version number in MicroProfile 1.2
            }
        }
        return 0; // the dependency name is not one of the Microprofile components
    }

    public static String[] getNameAndVersion(String featureName) {
        if (featureName == null) {
            return null;
        }
        String[] nameAndVersion = featureName.split("-", 2);
        if (nameAndVersion.length != 2) {
            return null;
        }
        if (nameAndVersion[1] == null) {
            return null;
        }
        nameAndVersion[0] = nameAndVersion[0].toLowerCase();
        if (nameAndVersion[1] == null || nameAndVersion[1].length() != 3) {
            return null;
        }
        return nameAndVersion;
    }
}
