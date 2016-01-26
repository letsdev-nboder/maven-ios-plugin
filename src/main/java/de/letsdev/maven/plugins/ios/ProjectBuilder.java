/**
 * Maven iOS Plugin
 *
 * User: sbott
 * Date: 19.07.2012
 * Time: 19:54:44
 *
 * This code is copyright (c) 2012 let's dev.
 * URL: http://www.letsdev.de
 * e-Mail: contact@letsdev.de
 */

package de.letsdev.maven.plugins.ios;

import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author let's dev
 */
public class ProjectBuilder {

    /**
     * @param properties Properties
     * @throws IOSException
     */
    public static void build(final Map<String, String> properties, MavenProject mavenProject) throws IOSException {
        // Make sure the source directory exists
        String projectName = Utils.buildProjectName(properties, mavenProject);

        File workDirectory = getWorkDirectory(properties, mavenProject, projectName);

        File projectDirectory = new File(workDirectory.toString() + File.separator + projectName);
        File assetsDirectory = null;
        File assetsTempDirectory = null;
        File newAssetsDirectory = null;

        //Rename assets directory
        if (properties.get(Utils.PLUGIN_PROPERTIES.ASSETS_DIRECTORY.toString()) != null) {
            assetsDirectory = new File(projectDirectory.toString() + File.separator + "assets");
            assetsTempDirectory = new File(projectDirectory.toString() + File.separator + "assets.tmp");
            newAssetsDirectory = new File(projectDirectory.toString() + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.ASSETS_DIRECTORY.toString()));

            if (assetsDirectory.exists() && !(newAssetsDirectory.toString().equalsIgnoreCase(assetsDirectory.toString()))){
                ProcessBuilder processBuilder = new ProcessBuilder("mv", assetsDirectory.toString(), assetsTempDirectory.toString());
                processBuilder.directory(projectDirectory);
                CommandHelper.performCommand(processBuilder);
            }

            if (newAssetsDirectory.exists() && !(newAssetsDirectory.toString().equalsIgnoreCase(assetsDirectory.toString()))) {
                ProcessBuilder processBuilder = new ProcessBuilder("mv", newAssetsDirectory.toString(), assetsDirectory.toString());
                processBuilder.directory(projectDirectory);
                CommandHelper.performCommand(processBuilder);
            }
        }

        File appIconsDirectory = null;
        File appIconsTempDirectory = null;
        File newAppIconsDirectory = null;

        //Rename appIcons directory
        if (properties.get(Utils.PLUGIN_PROPERTIES.APP_ICONS_DIRECTORY.toString()) != null) {
            appIconsDirectory = new File(projectDirectory.toString() + File.separator + "appIcons");
            appIconsTempDirectory = new File(projectDirectory.toString() + File.separator + "appIcons.tmp");
            newAppIconsDirectory = new File(projectDirectory.toString() + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.APP_ICONS_DIRECTORY.toString()));

            if (appIconsDirectory.exists() && !(newAppIconsDirectory.toString().equalsIgnoreCase(appIconsDirectory.toString()))){
                ProcessBuilder processBuilder = new ProcessBuilder("mv", appIconsDirectory.toString(), appIconsTempDirectory.toString());
                processBuilder.directory(projectDirectory);
                CommandHelper.performCommand(processBuilder);
            }

            if (newAppIconsDirectory.exists() && !(newAppIconsDirectory.toString().equalsIgnoreCase(appIconsDirectory.toString()))) {
                ProcessBuilder processBuilder = new ProcessBuilder("mv", newAppIconsDirectory.toString(), appIconsDirectory.toString());
                processBuilder.directory(projectDirectory);
                CommandHelper.performCommand(processBuilder);
            }
        }

        File targetDirectory = Utils.getTargetDirectory(mavenProject);
        String projectVersion = updateXcodeProjectInfoPlist(properties, mavenProject, projectName, workDirectory);

        File precompiledHeadersDir = createPrecompileHeadersDirectory(targetDirectory);

        //BEG clean the application
        ProcessBuilder processBuilder = cleanXcodeProject(properties, workDirectory);
        //END clean the application

        //unlock keychain
        unlockKeychain(properties, mavenProject, projectName, workDirectory, processBuilder);

        //if project contains cocoaPods dependencies, we install them first
        if (Utils.cocoaPodsEnabled(properties)) {
            installCocoaPodsDependencies(projectDirectory);
        }

        // Build the application
        List<String> buildParameters = generateBuildParameters(mavenProject, properties, targetDirectory, projectName, precompiledHeadersDir, false);
        processBuilder = new ProcessBuilder(buildParameters);
        processBuilder.directory(workDirectory);
        CommandHelper.performCommand(processBuilder);

        if (Utils.isiOSFramework(mavenProject, properties) || Utils.isMacOSFramework(properties)) {
            if (!Utils.isMacOSFramework(properties)) {
                //generate framework product also for iphonesimulator sdk
                buildParameters = generateBuildParameters(mavenProject, properties, targetDirectory, projectName, precompiledHeadersDir, true);
                processBuilder = new ProcessBuilder(buildParameters);
                processBuilder.directory(workDirectory);
                CommandHelper.performCommand(processBuilder);
            }

            String appName = properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString());
            String frameworkName = appName + ".framework";

            File targetWorkDirectory;
            if (Utils.isMacOSFramework(properties)) {
                targetWorkDirectory = new File(targetDirectory.toString() + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString()) + File.separator);
            }
            else {
                File targetWorkDirectoryIphone = new File(targetDirectory.toString() + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString()) + "-" + Utils.SDK_IPHONE_OS + File.separator);
                File targetWorkDirectoryIphoneSimulator = new File(targetDirectory.toString() + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString()) + "-" + Utils.SDK_IPHONE_SIMULATOR + File.separator);

                //if we'd build the framework with xcodebuild archive command, we have to export the framework from archive
                if (Utils.shouldBuildXCArchive(mavenProject, properties)) {
                    File archiveFile = new File(Utils.getArchiveName(projectName, mavenProject));
                    exportFrameworkArchive(archiveFile, targetWorkDirectoryIphone, appName, frameworkName);
                }

                // use lipo to merge framework binarys
                mergeFrameworkProducts(targetWorkDirectoryIphone, targetWorkDirectoryIphoneSimulator, appName, frameworkName);

                targetWorkDirectory = targetWorkDirectoryIphone;
            }

            // Zip Frameworks
            processBuilder = new ProcessBuilder("zip", "-r", "../" + properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString()) + "." + Utils.PLUGIN_SUFFIX.FRAMEWORK_ZIP.toString(), frameworkName);

            processBuilder.directory(targetWorkDirectory);
            CommandHelper.performCommand(processBuilder);
        }
        // Generate IPA
        else {

            //unlock keychain
            unlockKeychain(properties, mavenProject, projectName, workDirectory, processBuilder); //unlock it again, if during xcrun keychain is closed automatically again.

            if (properties.get(Utils.PLUGIN_PROPERTIES.BUILD_ID.toString()) != null) {
                projectVersion += "-b" + properties.get(Utils.PLUGIN_PROPERTIES.BUILD_ID.toString());
            }

            File appTargetPath = new File(targetDirectory + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString())
                    + "-" + Utils.SDK_IPHONE_OS + "/" + properties.get(Utils.PLUGIN_PROPERTIES.TARGET.toString()) + "." + Utils.PLUGIN_SUFFIX.APP);

            File newAppTargetPath = new File(targetDirectory + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString())
                    + "-" + Utils.SDK_IPHONE_OS + "/" + properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString()) + "." + Utils.PLUGIN_SUFFIX.APP);

            File ipaTargetPath = new File(targetDirectory + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString())
                    + "-" + Utils.SDK_IPHONE_OS + "/" + properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString()) + "-" + projectVersion + "." + Utils.PLUGIN_SUFFIX.IPA);

            File dsymTargetPath = new File(targetDirectory + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString())
                    + "-" + Utils.SDK_IPHONE_OS + "/" + properties.get(Utils.PLUGIN_PROPERTIES.TARGET.toString()) + "." + Utils.PLUGIN_SUFFIX.APP_DSYM);

            File newDsymTargetPath = new File(targetDirectory + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString())
                    + "-" + Utils.SDK_IPHONE_OS + "/" + properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString()) + "." + Utils.PLUGIN_SUFFIX.APP_DSYM);

            if (appTargetPath.exists() && !(appTargetPath.toString().equalsIgnoreCase(newAppTargetPath.toString()))) {
                processBuilder = new ProcessBuilder("mv", appTargetPath.toString(), newAppTargetPath.toString());
                processBuilder.directory(projectDirectory);
                CommandHelper.performCommand(processBuilder);
            }

            if (dsymTargetPath.exists() && !(dsymTargetPath.toString().equalsIgnoreCase(newDsymTargetPath.toString()))) {
                processBuilder = new ProcessBuilder("mv", dsymTargetPath.toString(), newDsymTargetPath.toString());
                processBuilder.directory(projectDirectory);
                CommandHelper.performCommand(processBuilder);
            }

            File ipaTmpDir = new File(targetDirectory, "ipa-temp-dir-" + UUID.randomUUID().toString());
            if(!ipaTmpDir.mkdir()){
                System.err.println("Could not create ipa temp dir at path = " + ipaTmpDir.getAbsolutePath());
            }

            if(!Utils.shouldBuildXCArchive(mavenProject, properties)){
                codeSignBeforeXcode6(properties, workDirectory, newAppTargetPath, ipaTargetPath, ipaTmpDir);
            }
            else {
               codeSignAfterXcode6(properties, mavenProject, workDirectory, newAppTargetPath, ipaTargetPath, ipaTmpDir);
            }
        }

        //lock keychain
        lockKeychain(properties);

        //Rename assets directory to origin
        renameAssetsDirectoryToOrigin(properties, projectDirectory, assetsDirectory, assetsTempDirectory, newAssetsDirectory);

        //Rename appIcons directory to origin
        renameAppIconsDirectoryToOrigin(properties, projectDirectory, appIconsDirectory, appIconsTempDirectory, newAppIconsDirectory);

        // Generate the the deploy plist file
        generateDeployPlistFile(properties, projectName, targetDirectory, projectVersion, processBuilder);
    }

    protected static String updateXcodeProjectInfoPlist(Map<String, String> properties, MavenProject mavenProject, String projectName, File workDirectory) throws IOSException {
        // Run agvtool to stamp marketing version
        String projectVersion = mavenProject.getVersion();

        if (properties.get(Utils.PLUGIN_PROPERTIES.IPA_VERSION.toString()) != null) {
            projectVersion = properties.get(Utils.PLUGIN_PROPERTIES.IPA_VERSION.toString());
        }
        //remove -SNAPSHOT in version number in order to prevent malformed version numbers in framework builds
        if (Utils.isiOSFramework(mavenProject, properties) || Utils.isMacOSFramework(properties)) {
            projectVersion = projectVersion.replace(Utils.BUNDLE_VERSION_SNAPSHOT_ID, "");
        }

        ProcessBuilder processBuilderNewMarketingVersion = new ProcessBuilder("agvtool", "new-marketing-version", projectVersion);
        processBuilderNewMarketingVersion.directory(workDirectory);
        CommandHelper.performCommand(processBuilderNewMarketingVersion);

        // Run agvtool to stamp version
        ProcessBuilder processBuilderNewVersion = new ProcessBuilder("agvtool", "new-version", "-all", projectVersion);
        processBuilderNewVersion.directory(workDirectory);
        CommandHelper.performCommand(processBuilderNewVersion);

        // Run PlistBuddy to stamp build if a build id is specified
        if (properties.get(Utils.PLUGIN_PROPERTIES.BUILD_ID.toString()) != null) {
            executePlistScript("write-buildnumber.sh",  properties.get(Utils.PLUGIN_PROPERTIES.BUILD_ID.toString()), workDirectory, projectName, properties);
        }

        // Run PlistBuddy to app icon name if a build id is specified
        if (properties.get(Utils.PLUGIN_PROPERTIES.APP_ICON_NAME.toString()) != null) {
            executePlistScript("write-app-icon-name.sh",  properties.get(Utils.PLUGIN_PROPERTIES.APP_ICON_NAME.toString()), workDirectory, projectName, properties);
        }

        // Run PlistBuddy to overwrite the bundle identifier in info plist
        if (properties.get(Utils.PLUGIN_PROPERTIES.BUNDLE_IDENTIFIER.toString()) != null) {
            executePlistScript("write-bundleidentifier.sh",  properties.get(Utils.PLUGIN_PROPERTIES.BUNDLE_IDENTIFIER.toString()), workDirectory, projectName, properties);
        }

        // Run PlistBuddy to overwrite the display name in info plist
        if (properties.get(Utils.PLUGIN_PROPERTIES.DISPLAY_NAME.toString()) != null) {
            executePlistScript("write-displayname.sh",  properties.get(Utils.PLUGIN_PROPERTIES.DISPLAY_NAME.toString()), workDirectory, projectName, properties);
        }
        return projectVersion;
    }

    protected static ProcessBuilder cleanXcodeProject(Map<String, String> properties, File workDirectory) throws IOSException {
        List<String> cleanParameters = new ArrayList<String>();
        cleanParameters.add("xcodebuild");
        cleanParameters.add("-alltargets");
        cleanParameters.add("-configuration");
        cleanParameters.add(properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString()));
        cleanParameters.add("clean");
        ProcessBuilder processBuilder = new ProcessBuilder(cleanParameters);
        processBuilder.directory(workDirectory);
        CommandHelper.performCommand(processBuilder);
        return processBuilder;
    }

    protected static File createPrecompileHeadersDirectory(File targetDirectory) {
        File precompiledHeadersDir = new File(targetDirectory, "precomp-dir-" + UUID.randomUUID().toString());
        if(!precompiledHeadersDir.mkdir()){
           System.err.println("Could not create precompiled headers dir at path = " + precompiledHeadersDir.getAbsolutePath());
        }
        return precompiledHeadersDir;
    }

    protected static void generateDeployPlistFile(Map<String, String> properties, String projectName, File targetDirectory, String projectVersion, ProcessBuilder processBuilder) throws IOSException {
        if ((properties.get(Utils.PLUGIN_PROPERTIES.DEPLOY_IPA_PATH.toString()) != null) && (properties.get(Utils.PLUGIN_PROPERTIES.DEPLOY_ICON_PATH.toString()) != null)) {
            final String deployPlistName = properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString()) + "-" + projectVersion + "." + Utils.PLUGIN_SUFFIX.PLIST;
            writeDeployPlistFile(targetDirectory, projectName, deployPlistName, properties, processBuilder);

        }
    }

    protected static void renameAppIconsDirectoryToOrigin(Map<String, String> properties, File projectDirectory, File appIconsDirectory, File appIconsTempDirectory, File newAppIconsDirectory) throws IOSException {
        if (properties.get(Utils.PLUGIN_PROPERTIES.APP_ICONS_DIRECTORY.toString()) != null) {
            if ((appIconsTempDirectory != null) && (appIconsDirectory.exists())) {
                ProcessBuilder processBuilderMv = new ProcessBuilder("mv", appIconsDirectory.toString(), newAppIconsDirectory.toString());
                processBuilderMv.directory(projectDirectory);
                CommandHelper.performCommand(processBuilderMv);
            }

            if ((appIconsTempDirectory != null) && (appIconsTempDirectory.exists())) {
                ProcessBuilder processBuilderMv = new ProcessBuilder("mv", appIconsTempDirectory.toString(), appIconsDirectory.toString());
                processBuilderMv.directory(projectDirectory);
                CommandHelper.performCommand(processBuilderMv);
            }
        }
    }

    protected static void renameAssetsDirectoryToOrigin(Map<String, String> properties, File projectDirectory, File assetsDirectory, File assetsTempDirectory, File newAssetsDirectory) throws IOSException {
        if (properties.get(Utils.PLUGIN_PROPERTIES.ASSETS_DIRECTORY.toString()) != null) {
            if ((assetsTempDirectory != null) && (assetsDirectory.exists())) {
                ProcessBuilder processBuilderMv = new ProcessBuilder("mv", assetsDirectory.toString(), newAssetsDirectory.toString());
                processBuilderMv.directory(projectDirectory);
                CommandHelper.performCommand(processBuilderMv);
            }

            if ((assetsTempDirectory != null) && (assetsTempDirectory.exists())) {
                ProcessBuilder processBuilderMv = new ProcessBuilder("mv", assetsTempDirectory.toString(), assetsDirectory.toString());
                processBuilderMv.directory(projectDirectory);
                    CommandHelper.performCommand(processBuilderMv);
            }
        }
    }

    protected static void lockKeychain(Map<String, String> properties) throws IOSException {
        if (properties.containsKey(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString()) && properties.containsKey(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PASSWORD.toString())) {
            String command = "security lock-keychain " + properties.get(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString());
            ProcessBuilder processBuilderLockKeyChain = new ProcessBuilder(CommandHelper.getCommand(command));
            CommandHelper.performCommand(processBuilderLockKeyChain);
        }
    }

    protected static void codeSignBeforeXcode6(Map<String, String> properties, File workDirectory, File newAppTargetPath, File ipaTargetPath, File ipaTmpDir) throws IOSException {
        ProcessBuilder processBuilderCodeSign = new ProcessBuilder(
                "xcrun",
                "--no-cache",  //disbale caching
                "-sdk",
                properties.get(Utils.PLUGIN_PROPERTIES.SDK.toString()),
                "PackageApplication",
                "-v",
                newAppTargetPath.toString(),
                "-o",
                ipaTargetPath.toString(),
                "--sign", properties.get(Utils.PLUGIN_PROPERTIES.CODE_SIGN_IDENTITY.toString())
        );

        processBuilderCodeSign.directory(workDirectory);
        processBuilderCodeSign.environment().put("TMPDIR", ipaTmpDir.getAbsolutePath());  //this is really important to avoid collisions, if not set /var/folders will be used here
        CommandHelper.performCommand(processBuilderCodeSign);
    }

    protected static void codeSignAfterXcode6(Map<String, String> properties, MavenProject mavenProject, File workDirectory, File newAppTargetPath, File ipaTargetPath, File ipaTmpDir) throws IOSException {
        /*
            xcodebuild -exportArchive -exportFormat format -archivePath xcarchivepath -exportPath destinationpath
                [-exportProvisioningProfile profilename] [-exportSigningIdentity identityname]
                [-exportInstallerIdentity identityname]
         */

        if(!ipaTargetPath.getParentFile().exists() && !ipaTargetPath.getParentFile().mkdirs()){
            throw new RuntimeException("Could not create directories for ipa target path=" + ipaTargetPath.getAbsolutePath());
        }

        ProcessBuilder processBuilderCodeSign = new ProcessBuilder(
                "xcodebuild",
                "-exportArchive",
                "-exportFormat",
                Utils.PLUGIN_SUFFIX.IPA.toString(),
                "-archivePath",
                Utils.getArchiveName(Utils.buildProjectName(properties, mavenProject), mavenProject),
                "-exportPath",
                ipaTargetPath.toString(),
                "-exportWithOriginalSigningIdentity"
        );

        processBuilderCodeSign.directory(workDirectory);
        processBuilderCodeSign.environment().put("TMPDIR", ipaTmpDir.getAbsolutePath());  //this is really important to avoid collisions, if not set /var/folders will be used here
        CommandHelper.performCommand(processBuilderCodeSign);
    }

    protected static File getWorkDirectory(Map<String, String> buildProperties, MavenProject mavenProject, String projectName) throws IOSException {
        File workDirectory = new File(mavenProject.getBasedir().toString() + File.separator
                + buildProperties.get(Utils.PLUGIN_PROPERTIES.SOURCE_DIRECTORY.toString()) + File.separator
                + projectName);

        if (!workDirectory.exists()) {
            throw new IOSException("Invalid sourceDirectory specified: " + workDirectory.getAbsolutePath());
        }
        return workDirectory;
    }

    private static void unlockKeychain(Map<String, String> properties, MavenProject mavenProject, String projectName, File workDirectory, ProcessBuilder processBuilder) throws IOSException {
        if (Utils.shouldCodeSign(mavenProject, properties) && properties.containsKey(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString()) && properties.containsKey(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PASSWORD.toString())) {
//            List<String> keychainParameters = new ArrayList<String>();
//            keychainParameters.add("security");
//            keychainParameters.add("unlock-keychain");
//            keychainParameters.add("-p");
//            keychainParameters.add(properties.get(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PASSWORD.toString()));
//            keychainParameters.add(properties.get(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString()));

            executeShellScript("unlock-keychain.sh", properties.get(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PASSWORD.toString()), properties.get(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString()), null, workDirectory, projectName, properties, processBuilder);

//            processBuilder = new ProcessBuilder(keychainParameters);
//            CommandHelper.performCommand(processBuilder);
        }
    }

    private static List<String> generateBuildParameters(MavenProject mavenProject, Map<String, String> properties, File targetDirectory, String projectName, File precompiledHeadersDir, boolean shouldUseIphoneSimulatorSDK) {
        List<String> buildParameters = new ArrayList<String>();
        buildParameters.add("xcodebuild");

        //if cocoa pods is enabled, we have to build the .xcworkspace file instead of .xcodeproj
        if (Utils.cocoaPodsEnabled(properties)) {
            buildParameters.add("-workspace");
            buildParameters.add(projectName + ".xcworkspace");
        }

        buildParameters.add("-sdk");
        if (shouldUseIphoneSimulatorSDK) {
            buildParameters.add(Utils.SDK_IPHONE_SIMULATOR);
            buildParameters.add("ARCHS=" + properties.get(Utils.PLUGIN_PROPERTIES.IPHONESIMULATOR_ARCHITECTURES.toString()));
            buildParameters.add("VALID_ARCHS=" + properties.get(Utils.PLUGIN_PROPERTIES.IPHONESIMULATOR_ARCHITECTURES.toString()));
        }
        else {
            buildParameters.add(properties.get(Utils.PLUGIN_PROPERTIES.SDK.toString()));
            buildParameters.add("ARCHS=" + properties.get(Utils.PLUGIN_PROPERTIES.IPHONEOS_ARCHITECTURES.toString()));
            buildParameters.add("VALID_ARCHS=" + properties.get(Utils.PLUGIN_PROPERTIES.IPHONEOS_ARCHITECTURES.toString()));
        }

        buildParameters.add("-configuration");
        buildParameters.add(properties.get(Utils.PLUGIN_PROPERTIES.CONFIGURATION.toString()));

        if (Utils.shouldBuildXCArchive(mavenProject, properties) && !shouldUseIphoneSimulatorSDK) {
            buildParameters.add("archive");
            buildParameters.add("-archivePath");
            buildParameters.add(Utils.getArchiveName(projectName, mavenProject));
        }
        else {
            buildParameters.add("SYMROOT=" + targetDirectory.getAbsolutePath());   //only possible without xcarchive
        }

        if (properties.containsKey(Utils.PLUGIN_PROPERTIES.SCHEME.toString())) {
            buildParameters.add("-scheme");
            buildParameters.add(properties.get(Utils.PLUGIN_PROPERTIES.SCHEME.toString()));
        }

        //if product should be code signed, we add flags for code signing
        if (Utils.shouldCodeSign(mavenProject, properties)) {

            if(Utils.shouldCodeSignWithResourceRules(mavenProject, properties)){
                buildParameters.add("CODE_SIGN_RESOURCE_RULES_PATH=$(SDKROOT)/ResourceRules.plist"); //since xcode 6.1 is necessary, if not set, app is not able to be signed with a key.
            }

            if (properties.containsKey(Utils.PLUGIN_PROPERTIES.CODE_SIGN_IDENTITY.toString())) {
                buildParameters.add("CODE_SIGN_IDENTITY=" + properties.get(Utils.PLUGIN_PROPERTIES.CODE_SIGN_IDENTITY.toString()));
            }
        }
        else {
            //otherwise we skip code signing
            buildParameters.add("CODE_SIGN_IDENTITY=");
            buildParameters.add("CODE_SIGNING_REQUIRED=NO");
        }

        if (Utils.shouldCodeSign(mavenProject, properties) && properties.containsKey(Utils.PLUGIN_PROPERTIES.PROVISIONING_PROFILE_UUID.toString())) {
            buildParameters.add("PROVISIONING_PROFILE=" + properties.get(Utils.PLUGIN_PROPERTIES.PROVISIONING_PROFILE_UUID.toString()));
        }

        if (properties.get(Utils.PLUGIN_PROPERTIES.BUNDLE_IDENTIFIER.toString()) != null) {
            buildParameters.add("PRODUCT_BUNDLE_IDENTIFIER=" + properties.get(Utils.PLUGIN_PROPERTIES.BUNDLE_IDENTIFIER.toString()));
        }

        if (!Utils.cocoaPodsEnabled(properties) && properties.containsKey(Utils.PLUGIN_PROPERTIES.APP_NAME.toString())) {
            buildParameters.add("PRODUCT_NAME=" + properties.get(Utils.PLUGIN_PROPERTIES.APP_NAME.toString()));
        }

        String target = null;
        if (properties.containsKey(Utils.PLUGIN_PROPERTIES.TARGET.toString())) {
            target = properties.get(Utils.PLUGIN_PROPERTIES.TARGET.toString());
        }

        //only if target tag is present and we are not building via xcArchive, we set the target switch
        if(!Utils.shouldBuildXCArchive(mavenProject, properties) && target != null){ //from XCode > Version 7 target should not be used any more. Use scheme instead!
            // Add target. Uses target 'framework' to build Frameworks.
            buildParameters.add("-target");
            buildParameters.add(target);
        }

        //buildParameters.add("SHARED_PRECOMPS_DIR=" + precompiledHeadersDir.getAbsolutePath());   //this is really important to avoid collisions, if not set /var/folders will be used here
        if (Utils.shouldCodeSign(mavenProject, properties) && properties.containsKey(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString())) {
            buildParameters.add("OTHER_CODE_SIGN_FLAGS=--keychain " + properties.get(Utils.PLUGIN_PROPERTIES.KEYCHAIN_PATH.toString()));
        }

        if (properties.containsKey(Utils.PLUGIN_PROPERTIES.GCC_PREPROCESSOR_DEFINITIONS.toString())) {
            buildParameters.add("GCC_PREPROCESSOR_DEFINITIONS=" + properties.get(Utils.PLUGIN_PROPERTIES.GCC_PREPROCESSOR_DEFINITIONS.toString()));
        }

        return buildParameters;
    }

    private static void mergeFrameworkProducts(File targetWorkDirectoryIphone, File targetWorkDirectoryIphoneSimulator, String appName, String frameworkName) {
        // Run shell-script from resource-folder.
        try {
            final String scriptName = "merge-framework-products";

            final String iphoneosFrameworkProductPath = targetWorkDirectoryIphone.toString() + "/" + frameworkName + "/" + appName;
            final String iphoneSimulatorFrameworkProductPath = targetWorkDirectoryIphoneSimulator.toString() + "/" + frameworkName + "/" + appName;
            final String mergedFrameworkPath = targetWorkDirectoryIphone.toString() + "/" + frameworkName + "/" + appName;

            File tempFile = File.createTempFile(scriptName, "sh");
            InputStream inputStream = ProjectBuilder.class.getResourceAsStream("/META-INF/" + scriptName + ".sh");
            OutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();

            ProcessBuilder processBuilder = new ProcessBuilder("sh", tempFile.getAbsoluteFile().toString(),
                    iphoneosFrameworkProductPath,
                    iphoneSimulatorFrameworkProductPath,
                    mergedFrameworkPath);

            processBuilder.directory(targetWorkDirectoryIphone);
            CommandHelper.performCommand(processBuilder);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void exportFrameworkArchive(File archiveFile, File targetFrameworkPath, String appName, String frameworkName) {
        // Run shell-script from resource-folder.
        try {
            final String scriptName = "export-framework-archive";

            targetFrameworkPath.mkdir();
            final String iphoneosFrameworkPath = targetFrameworkPath.toString() + "/" + frameworkName;
            final String iphoneosFrameworkProductPath = targetFrameworkPath.toString() + "/" + frameworkName + "/" + appName;

            File tempFile = File.createTempFile(scriptName, "sh");
            InputStream inputStream = ProjectBuilder.class.getResourceAsStream("/META-INF/" + scriptName + ".sh");
            OutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();

            ProcessBuilder processBuilder = new ProcessBuilder("sh", tempFile.getAbsoluteFile().toString(),
                    archiveFile.toString(),
                    frameworkName,
                    targetFrameworkPath.toString(),
                    iphoneosFrameworkPath,
                    iphoneosFrameworkProductPath);

            processBuilder.directory(targetFrameworkPath);
            CommandHelper.performCommand(processBuilder);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void executePlistScript(String scriptName, String value, File workDirectory, String projectName, final Map<String, String> properties) throws IOSException {
        String infoPlistFile = workDirectory + File.separator + projectName + File.separator + projectName + "-Info.plist";

        if (properties.get(Utils.PLUGIN_PROPERTIES.INFO_PLIST.toString()) != null) {
            infoPlistFile = workDirectory + File.separator + properties.get(Utils.PLUGIN_PROPERTIES.INFO_PLIST.toString());
        }

        // Run shell-script from resource-folder.
        try {
            File tempFile = File.createTempFile(scriptName, "sh");

            InputStream inputStream = ProjectBuilder.class
                    .getResourceAsStream("/META-INF/" + scriptName);
            OutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {

                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();

            ProcessBuilder processBuilder = new ProcessBuilder("sh", tempFile.getAbsoluteFile().toString(), infoPlistFile, value);

            processBuilder.directory(workDirectory);
            CommandHelper.performCommand(processBuilder);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void executeShellScript(String scriptName, String value1, String value2, String value3, File workDirectory, String projectName, final Map<String, String> properties, ProcessBuilder processBuilder) throws IOSException {

        // Run shell-script from resource-folder.
        try {
            File tempFile = File.createTempFile(scriptName, "sh");

            InputStream inputStream = ProjectBuilder.class
                    .getResourceAsStream("/META-INF/" + scriptName);
            OutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {

                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();

            if(value1 == null){
                value1 = "";
            }

            if(value2 == null){
                value2 = "";
            }

            if(value3 == null){
                value3 = "";
            }

            processBuilder = new ProcessBuilder("sh", tempFile.getAbsoluteFile().toString(), value1, value2, value3);

            processBuilder.directory(workDirectory);
            CommandHelper.performCommand(processBuilder);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeDeployPlistFile(File targetDirectory, String projectName, String deployPlistName, final Map<String, String> properties, ProcessBuilder processBuilder) throws IOSException {
        // Run shell-script from resource-folder.
        try {
            final String scriptName = "write-deploy-plist";

            final String ipaLocation = properties.get(Utils.PLUGIN_PROPERTIES.DEPLOY_IPA_PATH.toString());
            final String iconLocation = properties.get(Utils.PLUGIN_PROPERTIES.DEPLOY_ICON_PATH.toString());
            final String displayName = properties.get(Utils.PLUGIN_PROPERTIES.DISPLAY_NAME.toString());
            final String bundleIdentifier = properties.get(Utils.PLUGIN_PROPERTIES.BUNDLE_IDENTIFIER.toString());
            final String bundleVersion = properties.get(Utils.PLUGIN_PROPERTIES.IPA_VERSION.toString());

            File tempFile = File.createTempFile(scriptName, "sh");

            InputStream inputStream = ProjectBuilder.class.getResourceAsStream("/META-INF/" + scriptName);
            OutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();

            processBuilder = new ProcessBuilder("sh", tempFile.getAbsoluteFile().toString(),
                    deployPlistName,
                    ipaLocation,
                    iconLocation,
                    displayName,
                    bundleIdentifier,
                    bundleVersion);

            processBuilder.directory(targetDirectory);
            CommandHelper.performCommand(processBuilder);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void installCocoaPodsDependencies(File projectDirectory) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("pod", "install");
            processBuilder.directory(projectDirectory);
            CommandHelper.performCommand(processBuilder);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
