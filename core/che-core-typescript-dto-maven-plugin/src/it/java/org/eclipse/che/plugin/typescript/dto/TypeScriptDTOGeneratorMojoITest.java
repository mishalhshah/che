/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.typescript.dto;

import org.eclipse.che.api.core.util.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Integration test of TypeScriptDTOGeneratorMojo
 * It uses docker to launch TypeScript compiler and then launch JavaScript tests to ensure generator has worked correctly
 * @author Florent Benoit
 */
public class TypeScriptDTOGeneratorMojoITest {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TypeScriptDTOGeneratorMojoITest.class);

    /**
     * DTO Generated file
     */
    private static final String GENERATED_DTO_NAME = "my-typescript-test-module.ts";

    /**
     * DTO new name
     */
    private static final String DTO_FILENAME = "dto.ts";

    /**
     * DTO test name
     */
    private static final String DTO_SPEC_FILENAME = "dto.spec.ts";

    /**
     * Target folder of maven.
     */
    private Path buildDirectory;

    /**
     * Path to the package.json file used to setup typescript compiler
     */
    private Path dtoSpecJsonPath;

    /**
     * Path to the package.json file used to setup typescript compiler
     */
    private Path packageJsonPath;

    /**
     * Root directory for our tests
     */
    private Path rootPath;

    /**
     * Init folders
     */
    @BeforeClass
    public void init() throws URISyntaxException, IOException, InterruptedException {
        // setup packages
        this.packageJsonPath = new File(TypeScriptDTOGeneratorMojoITest.class.getClassLoader().getResource("package.json").toURI()).toPath();

        this.rootPath = this.packageJsonPath.getParent();

        // target folder
        String buildDirectoryProperty = System.getProperty("buildDirectory");
        if (buildDirectoryProperty != null) {
            buildDirectory = new File(buildDirectoryProperty).toPath();
        } else {
            buildDirectory = packageJsonPath.getParent().getParent();
        }

        LOG.info("Using building directory {0}", buildDirectory);
    }

    /**
     * Generates a docker exec command used to launch node commands
     * @return list of command parameters
     */
    protected List<String> getDockerExec() {
        // setup command line
        List<String> command = new ArrayList();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("-v");
        command.add(rootPath.toString() + ":/usr/src/app");
        command.add("-w");
        command.add("/usr/src/app");
        command.add("node:6");
        command.add("/bin/sh");
        command.add("-c");

        return command;
    }

    /**
     * Setup typescript compiler by downloading the dependencies
     * @throws IOException if unable to start process
     * @throws InterruptedException if unable to wait the end of the process
     */
    @Test(groups = {"tools"})
    protected void installTypeScriptCompiler() throws IOException, InterruptedException {

        // setup command line
        List<String> command = getDockerExec();

        // avoid root permissions in generated files
        if (SystemInfo.isLinux()) {

            // grab user id and gid
            ProcessBuilder uidProcessBuilder = new ProcessBuilder("id", "-u");
            Process processId = uidProcessBuilder.start();
            int resultId = processId.waitFor();
            String uid = "";
            try (BufferedReader outReader = new BufferedReader(new InputStreamReader(processId.getInputStream()))) {
                uid = String.join(System.lineSeparator(), outReader.lines().collect(toList()));
            } catch (Exception error) {
                throw new IllegalStateException("Unable to get uid" + uid);
            }

            if (resultId != 0) {
                throw new IllegalStateException("Unable to get uid" + uid);
            }

            try {
                Integer.valueOf(uid);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("The uid is not a number" + uid);
            }

            ProcessBuilder gidProcessBuilder = new ProcessBuilder("id", "-g");
            Process processGid = gidProcessBuilder.start();
            int resultGid = processGid.waitFor();
            String gid = "";
            try (BufferedReader outReader = new BufferedReader(new InputStreamReader(processGid.getInputStream()))) {
                gid = String.join(System.lineSeparator(), outReader.lines().collect(toList()));
            } catch (Exception error) {
                throw new IllegalStateException("Unable to get gid" + gid);
            }

            if (resultGid != 0) {
                throw new IllegalStateException("Unable to get gid" + gid);
            }

            try {
                Integer.valueOf(gid);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("The uid is not a number" + gid);
            }

            LOG.info("Using uid '" + uid + "' and gid '" + gid + "'.");

            command.add("groupadd -g " + gid + " user && useradd -u" + uid + " -g user user && (chown --silent -R user.user /usr/src/app || true) && cd /usr/src/app/ && npm install && (chown --silent -R user.user /usr/src/app || true)");
        } else {
            command.add("npm install");
        }
        // setup typescript compiler
        ProcessBuilder processBuilder = new ProcessBuilder().command(command).directory(rootPath.toFile()).redirectErrorStream(true).inheritIO();
        Process process = processBuilder.start();

        LOG.info("Installing TypeScript compiler in {0}", rootPath);
        int resultProcess = process.waitFor();

        if (resultProcess != 0) {
            throw new IllegalStateException("Install of TypeScript has failed");
        }
        LOG.info("TypeScript compiler installed.");

    }

    /**
     * Starts tests by compiling first generated DTO from maven plugin
     * @throws IOException if unable to start process
     * @throws InterruptedException if unable to wait the end of the process
     */
    @Test(dependsOnGroups = "tools")
    public void compileDTOAndLaunchTests() throws IOException, InterruptedException {

        // search DTO
        Path p = this.buildDirectory;
        final int maxDepth = 10;
        Stream<Path> matches = java.nio.file.Files.find(p, maxDepth, (path, basicFileAttributes) -> path.getFileName().toString().equals(GENERATED_DTO_NAME));

        // take first
        Optional<Path> optionalPath = matches.findFirst();
        if (!optionalPath.isPresent()) {
            throw new IllegalStateException("Unable to find generated DTO file named '" + GENERATED_DTO_NAME + "'. Check it has been generated first");
        }

        Path generatedDtoPath = optionalPath.get();

        //copy it in test resources folder where package.json is
        java.nio.file.Files.copy(generatedDtoPath, this.rootPath.resolve(DTO_FILENAME), StandardCopyOption.REPLACE_EXISTING);

        // setup command line
        List<String> command = getDockerExec();

        // avoid root permissions in generated files
        if (SystemInfo.isLinux()) {
            command.add("groupadd user && useradd -g user user && (chown --silent -R user.user /usr/src/app || true) && npm test && (chown --silent -R user.user /usr/src/app || true)");
        } else {
            command.add("npm test");
        }
        // setup typescript compiler
        ProcessBuilder processBuilder = new ProcessBuilder().command(command).directory(rootPath.toFile()).redirectErrorStream(true).inheritIO();
        Process process = processBuilder.start();

        LOG.info("Starting TypeScript tests...");
        int resultProcess = process.waitFor();

        if (resultProcess != 0) {
            throw new IllegalStateException("DTO has failed to compile");
        }
        LOG.info("TypeScript tests OK");

    }

}
