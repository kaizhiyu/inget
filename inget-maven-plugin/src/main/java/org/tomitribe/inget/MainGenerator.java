/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */
package org.tomitribe.inget;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.tomitribe.inget.client.ClientGenerator;
import org.tomitribe.inget.cmd.CmdGenerator;
import org.tomitribe.inget.common.Authentication;
import org.tomitribe.inget.common.Configuration;
import org.tomitribe.inget.common.CustomTypeSolver;
import org.tomitribe.inget.common.Utils;
import org.tomitribe.inget.model.ModelGenerator;
import org.tomitribe.inget.resource.ResourcesGenerator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * @goal generate-sources
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class MainGenerator extends AbstractMojo {

    @Parameter(property = "generate.model_package", required = true)
    private String modelPackage;

    @Parameter(property = "generate.resource_package")
    private String resourcePackage;

    @Parameter(property = "generate.generate_model", defaultValue = "false")
    private Boolean generateModel;

    @Parameter(property = "generate.generate_resources", defaultValue = "false")
    private Boolean generateResources;

    @Parameter(property = "generate.generate_client", defaultValue = "false")
    private Boolean generateClient;

    @Parameter(property = "generate.generate_cli", defaultValue = "false")
    private Boolean generateCli;

    @Parameter(property = "generate.client_name", defaultValue = "ResourceClient")
    private String clientName;

    @Parameter(property = "generate.resource_suffix")
    private String resourceSuffix;

    @Parameter(property = "generate.model_suffix", defaultValue = "Model")
    private String modelSuffix;

    @Parameter(property = "generate.cmdline_name")
    private String cmdLineName;

    @Parameter(property = "generate.authentication")
    private String authentication;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        project.setArtifactFilter(new AndArtifactFilter());
        Set<Artifact> artifacts = project.getArtifacts();
        final String generatedSources = project.getBuild().getDirectory() + File.separator + "generated-sources";
        project.addCompileSourceRoot(generatedSources);
        Configuration.modelSources = project.getBuild().getSourceDirectory();
        Configuration.resourceSources = project.getBuild().getSourceDirectory();
        Configuration.generatedSources = generatedSources;
        Configuration.modelPackage = modelPackage;
        Configuration.resourcePackage = resourcePackage;
        Configuration.clientName = clientName;
        Configuration.resourceSuffix = resourceSuffix;
        Configuration.modelSuffix = modelSuffix;
        Configuration.tempSource = project.getBuild().getDirectory() + File.separator + "temp-source";

        if (cmdLineName != null) {
            Configuration.cmdLineName = cmdLineName;
        } else {
            Configuration.cmdLineName = project.getArtifactId();
        }

        if (authentication != null) {
            if (authentication.equalsIgnoreCase(Authentication.BASIC.name())) {
                Configuration.authentication = Authentication.BASIC;
            }

            if (authentication.equalsIgnoreCase(Authentication.SIGNATURE.name())) {
                Configuration.authentication = Authentication.SIGNATURE;
            }
        }

        try {
            generateModel(artifacts);
            generateResources(artifacts);

            FileUtils.mkdir(generatedSources);
            // Only after resolving the model and resource paths
            CustomTypeSolver.init();

            if (generateClient) {
                requireResourcePackage();
                getLog().info("Started Client Code Generation.");
                ClientGenerator.execute();
                getLog().info("Finished Client Code Generation.");
                Configuration.clientSources = Configuration.generatedSources;
            }

            if (generateCli) {
                boolean clientExistsInCurrentProject = new File(Configuration.getClientPath()).exists();
                if (clientExistsInCurrentProject) {
                    Configuration.clientSources = Configuration.getClientPath();
                } else {
                    List<Artifact> clientDependencies = artifacts.stream()
                            .filter(a -> hasClient(a.getFile()))
                            .collect(Collectors.toList());

                    if (clientDependencies.size() == 0) {
                        throw new MojoExecutionException(
                                "Clients were not found. Generate the client adding the 'resourcePackage' and 'generateClient' as true.");
                    }

                    clientDependencies.forEach(m -> extractJavaFiles(m.getFile()));
                    Configuration.resourceSources = Configuration.tempSource;
                    Configuration.clientSources = Configuration.tempSource;
                }
                Configuration.cmdPackage = Configuration.resourcePackage + ".cmd";
                getLog().info("Started Command Code Generation.");
                CmdGenerator.execute();
                getLog().info("Finished Command Code Generation.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void generateResources(Set<Artifact> artifacts) throws IOException, MojoExecutionException {
        if (generateResources) {
            requireModelPackage();

            if (resourcePackage == null) {
                Configuration.resourcePackage = "org.tomitribe.resources";
            }

            File resourceFolder = new File(Configuration.getResourcePath());
            boolean resourcesExistsInCurrentProject = resourceFolder.exists();
            if (!resourcesExistsInCurrentProject) {
                resourceFolder.mkdirs();
            }

            getLog().info("Started Resource Code Generation.");
            ResourcesGenerator.execute();
            getLog().info("Finished Resource Code Generation.");
            Configuration.resourceSources = Configuration.generatedSources;
        } else {
            if (resourcePackage != null) {
                List<String> compileSourceRoots = project.getCompileSourceRoots();
                if (compileSourceRoots != null) {
                    for (String source : compileSourceRoots) {
                        File folder = new File(source, Utils.transformPackageToPath(Configuration.resourcePackage));
                        if (folder.exists()) {
                            Configuration.resourceSources = source;
                            return;
                        }
                    }
                }

                List<Artifact> resourceDependencies = artifacts.stream()
                        .filter(a -> hasResources(a.getFile())).collect(Collectors.toList());

                if (resourceDependencies.size() == 0) {
                    throw new MojoExecutionException(
                            "Resources were not found. Add the correct 'resourcePackage' for " +
                                    "this project or add a jar with the .java files for the resources.");
                } else {
                    resourceDependencies.stream().forEach(m -> extractJavaFiles(m.getFile()));
                    Configuration.resourceSources = Configuration.tempSource;
                }
            }
        }
        Configuration.clientSources = Configuration.resourceSources;
    }

    private void generateModel(Set<Artifact> artifacts) throws IOException, MojoExecutionException {
        if (generateModel) {
            requireModelPackage();

            File modelFolder = new File(Configuration.getModelPath());
            boolean existsInCurrentProject = modelFolder.exists();
            if (!existsInCurrentProject) {
                modelFolder.mkdirs();
            }
            getLog().info("Started Model Code Generation.");
            ModelGenerator.execute();
            getLog().info("Finished Model Code Generation.");
        } else {
            if (modelPackage != null) {
                List<String> compileSourceRoots = project.getCompileSourceRoots();
                if (compileSourceRoots != null) {
                    for (String source : compileSourceRoots) {
                        File folder = new File(source, Utils.transformPackageToPath(Configuration.modelPackage));
                        if (folder.exists()) {
                            Configuration.modelSources = source;
                            return;
                        }
                    }
                }

                List<Artifact> modelDependencies = artifacts.stream()
                        .filter(a -> hasModel(a.getFile())).collect(Collectors.toList());

                if (modelDependencies.size() == 0) {
                    throw new MojoExecutionException(
                            "Model was not found. Add the correct 'modelPackage' for " +
                                    "this project or add a jar with the .java files for the model.");
                }

                modelDependencies.stream().forEach(m -> extractJavaFiles(m.getFile()));
                Configuration.modelSources = Configuration.tempSource;
            }
        }
    }

    private boolean hasModel(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile);
            Enumeration<? extends JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                if (zipEntry.getName().equals(Utils.transformPackageToPath(Configuration.modelPackage) + File.separator)) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private boolean hasResources(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile);
            Enumeration<? extends JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                if (zipEntry.getName().equals(Utils.transformPackageToPath(Configuration.resourcePackage) + File.separator)) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private boolean hasClient(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile);
            Enumeration<? extends JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                String pkg = Utils.transformPackageToPath(Configuration.resourcePackage) + File.separator + "client";
                if (zipEntry.getName().equals(pkg) || zipEntry.getName().equals(pkg + File.separator)) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private void extractJavaFiles(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile);
            Enumeration<? extends JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                if (zipEntry.getName().endsWith(".java")) {
                    InputStream is = jar.getInputStream(zipEntry);
                    File generatedSources = new File(Configuration.tempSource);
                    java.io.File output = new java.io.File(generatedSources, java.io.File.separator + zipEntry.getName());
                    if (!output.getParentFile().exists()) {
                        output.getParentFile().mkdirs();
                    }
                    output.createNewFile();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(output);
                    while (is.available() > 0) {  // write contents of 'is' to 'fos'
                        fos.write(is.read());
                    }
                    fos.close();
                    is.close();
                }
            }
            jar.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void requireResourcePackage() throws MojoExecutionException {
        if (resourcePackage == null) {
            throw new MojoExecutionException(
                    "The 'resourcePackage' configuration was not found.");
        }
    }

    private void requireModelPackage() throws MojoExecutionException {
        if (modelPackage == null) {
            throw new MojoExecutionException(
                    "The 'modelPackage' configuration was not found.");
        }
    }
}
