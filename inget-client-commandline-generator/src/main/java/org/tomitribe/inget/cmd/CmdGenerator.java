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
package org.tomitribe.inget.cmd;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;
import com.google.googlejavaformat.java.RemoveUnusedImports;
import org.apache.commons.lang3.text.WordUtils;
import org.tomitribe.inget.cmd.base.ModelType;
import org.tomitribe.inget.common.Authentication;
import org.tomitribe.inget.common.Configuration;
import org.tomitribe.inget.common.CustomTypeSolver;
import org.tomitribe.inget.common.ImportManager;
import org.tomitribe.inget.common.Operation;
import org.tomitribe.inget.common.Reformat;
import org.tomitribe.inget.common.RemoveDuplicateImports;
import org.tomitribe.inget.common.TemplateUtil;
import org.tomitribe.inget.common.Utils;
import org.tomitribe.util.Join;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.tomitribe.inget.common.Utils.formatCamelCaseTo;

public class CmdGenerator {

    private static final String BASE_OUTPUT_PACKAGE = Configuration.resourcePackage + ".cmd.base";

    private CmdGenerator(){
    }

    public static void execute() throws IOException {
        final List<File> sourceClients = Utils.getClient();

        generateBaseCommand();
        JavaParser.setStaticConfiguration(
                new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(CustomTypeSolver.get())));

        final Map<String, List<String>> groups = new HashMap<>();
        for (final File sourceClient : sourceClients) {
            final CompilationUnit client = JavaParser.parse(sourceClient);
            if (!(client.toString().contains("javax.ws.rs") && client.toString().contains("@Path"))) {
                continue;
            }
            final ClassOrInterfaceDeclaration clientClass = Utils.getClazz(client);
            final String replaceValue = Configuration.resourceSuffix == null ?
                    "Client" : Configuration.resourceSuffix + "Client";
            final String clientGroup =
                    clientClass.getNameAsString().replace(replaceValue, "");

            final List<MethodDeclaration> methods = clientClass.getMethods();
            final List<String> commands =
                    methods.stream()
                            .map(methodDeclaration -> generateCommandFromClientMethod(methodDeclaration, clientGroup))
                            .collect(Collectors.toList());

            groups.put(clientGroup, commands);
        }

        generateCli(groups);
    }

    private static void generateBaseCommand() throws IOException {
        final CompilationUnit baseCommand = JavaParser.parse(TemplateUtil.readTemplate("DefaultCommand.java"));
        baseCommand.setPackageDeclaration(BASE_OUTPUT_PACKAGE);
        Utils.addGeneratedAnnotation(baseCommand, Utils.getClazz(baseCommand), null, CmdGenerator.class);
        ClassOrInterfaceDeclaration commandClass = baseCommand.getClassByName("DefaultCommand").get();
        addCommandOptions(commandClass);
        buildConfiguration(commandClass);
        updateConfigWithNewValueMethod(commandClass);
        readValueConfigurationValueIfNotProvidedMethod(commandClass);
        CompilationUnit modifiedClassUnit = commandClass.findCompilationUnit().get();
        addImports(modifiedClassUnit);
        String baseCmd = modifiedClassUnit.toString().replaceAll("%cmdLineName%", Configuration.cmdLineName);
        Utils.save("DefaultCommand.java", BASE_OUTPUT_PACKAGE, baseCmd);
    }

    private static void addImports(CompilationUnit modifiedClassUnit) {
        modifiedClassUnit.addImport(ImportManager.getImport("ClientConfiguration"));
        if (Configuration.authentication == Authentication.BASIC) {
            modifiedClassUnit.addImport(ImportManager.getImport("BasicConfiguration"));
        }
        if (Configuration.authentication == Authentication.SIGNATURE) {
            modifiedClassUnit.addImport(ImportManager.getImport("SignatureConfiguration"));
        }
    }

    private static void buildConfiguration(ClassOrInterfaceDeclaration commandClass) {
        MethodDeclaration buildConfig = commandClass.getMethodsByName("buildConfiguration").stream().findFirst().get();
        BlockStmt body = buildConfig.getBody().get();

        if (Configuration.authentication == Authentication.BASIC) {
            body.asBlockStmt().addStatement(JavaParser.parseStatement("BasicConfiguration basicConfiguration = null;"));

            String basic = "if (username != null && password != null) {" +
                    "            basicConfiguration = BasicConfiguration.builder().header(\"Authorization\").prefix(\"Basic\").username(username)" +
                    "                    .password(password).build();" +
                    "            builder.basic(basicConfiguration);" +
                    "        }";
            body.asBlockStmt().addStatement(JavaParser.parseStatement(basic));
        }

        if (Configuration.authentication == Authentication.SIGNATURE) {
            body.asBlockStmt().addStatement(JavaParser.parseStatement("SignatureConfiguration signatureConfiguration = null;"));

            String signature =
                    "if (keyId != null && keyLocation != null) {\n" +
                            "signatureConfiguration = SignatureConfiguration.builder().keyId(keyId).keyLocation(keyLocation)\n" +
                            ".signatureDetails(signatureDetails).header(\"Authorization\").prefix(\"Signature\").build();\n" +
                            "builder.signature(signatureConfiguration);\n" +
                            "}";
            body.asBlockStmt().addStatement(JavaParser.parseStatement(signature));
        }

        body.asBlockStmt().addStatement(JavaParser.parseStatement("return builder.build();"));
    }

    private static void updateConfigWithNewValueMethod(ClassOrInterfaceDeclaration commandClass) {
        MethodDeclaration updateConfig = commandClass.getMethodsByName("updateConfigWithNewValue").stream().findFirst().get();
        if (Configuration.authentication == Authentication.BASIC) {
            String username =
                    "if (username != null) {" +
                            "   conf.put(\"basic.username\", username);" +
                            "}";

            String password =
                    "if (password != null) {" +
                            "conf.put(\"basic.password\", java.util.Base64.getEncoder().encodeToString(password.getBytes()));" +
                            "}";

            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(username));
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(password));
        }

        if (Configuration.authentication == Authentication.SIGNATURE) {
            String keyId =
                    "if (keyId != null) {" +
                            "    conf.put(\"signature.key-id\", keyId);" +
                            "}";
            String keyLocation =
                    "if (keyLocation != null) {" +
                            "    conf.put(\"signature.key-location\", keyLocation);" +
                            "}";
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(keyId));
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(keyLocation));
        }
    }

    private static void readValueConfigurationValueIfNotProvidedMethod(ClassOrInterfaceDeclaration commandClass) {
        MethodDeclaration updateConfig = commandClass.getMethodsByName("readValueConfigurationValueIfNotProvided").stream().findFirst().get();
        if (Configuration.authentication == Authentication.BASIC) {
            String username =
                    " if (username == null && conf.containsKey(\"basic.username\")) {\n" +
                            "         username = conf.getProperty(\"basic.username\");\n" +
                            "}";

            String password =
                    "if (password == null && conf.containsKey(\"basic.password\")) {\n" +
                            "password = new String(java.util.Base64.getDecoder().decode(conf.getProperty(\"basic.password\").getBytes()));" +
                            "}";
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(username));
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(password));
        }

        if (Configuration.authentication == Authentication.SIGNATURE) {
            String keyId =
                    "if (keyId == null && conf.containsKey(\"signature.key-id\")) {" +
                            "         keyId = conf.getProperty(\"signature.key-id\");" +
                            "}";

            String keyLocation =
                    " if (keyLocation == null && conf.containsKey(\"signature.key-location\")) {\n" +
                            "         keyLocation = conf.getProperty(\"signature.key-location\");\n" +
                            " }";
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(keyId));
            updateConfig.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(keyLocation));
        }
    }

    private static void addCommandOptions(ClassOrInterfaceDeclaration commandClass) {
        FieldDeclaration f = null;

        if (Configuration.authentication == Authentication.BASIC) {
            f = commandClass.addField("String", "username", Modifier.PRIVATE);
            f.addAnnotation(JavaParser.parseAnnotation("@Option(name = {\"-u\", \"--username\"}, type = OptionType.GLOBAL)"));
            f = commandClass.addField("String", "password", Modifier.PRIVATE);
            f.addAnnotation(JavaParser.parseAnnotation("@Option(name = {\"-p\", \"--password\"}, type = OptionType.GLOBAL)"));
        }

        if (Configuration.authentication == Authentication.SIGNATURE) {
            f = commandClass.addField("String", "keyId", Modifier.PRIVATE);
            f.addAnnotation(JavaParser.parseAnnotation("@Option(name = {\"-k\", \"--key-id\"}, type = OptionType.GLOBAL)"));
            f = commandClass.addField("String", "keyLocation", Modifier.PRIVATE);
            f.addAnnotation(JavaParser.parseAnnotation("@Option(name = {\"-n\", \"--key-location\"}, type = OptionType.GLOBAL)"));
            f = commandClass.addField("boolean", "signatureDetails", Modifier.PRIVATE);
            f.addAnnotation(JavaParser.parseAnnotation("@Option(name = {\"-s\", \"--signature-details\"}, type = OptionType.GLOBAL)"));
        }
    }

    private static String generateCommandFromClientMethod(final MethodDeclaration clientMethod,
                                                          final String clientGroup) {
        final CompilationUnit command = new CompilationUnit(Configuration.resourcePackage + ".cmd");

        final String commandClassName = clientGroup + WordUtils.capitalize(clientMethod.getNameAsString()) + "Cmd";
        command.addClass(commandClassName);

        final ClassOrInterfaceDeclaration commandClass =
                command.getClassByName(commandClassName).orElseThrow(IllegalArgumentException::new);
        addCommandAnnotation(clientMethod.getNameAsString(), command, commandClass);
        extendCommandBaseClass(command, commandClass);
        addCommandFlags(command, commandClass, clientGroup, clientMethod);

        save(commandClassName, command);

        return commandClassName;
    }

    private static void addCommandAnnotation(final String clientMethodName,
                                             final CompilationUnit command,
                                             final ClassOrInterfaceDeclaration commandClass) {
        final NormalAnnotationExpr commandAnnotation = new NormalAnnotationExpr();
        commandAnnotation.setName("Command");
        commandAnnotation.addPair("name", "\"" + formatCamelCaseTo(clientMethodName, "-") + "\"");
        commandClass.addAnnotation(commandAnnotation);
        command.addImport(ImportManager.getImport("Command"));
    }

    private static void extendCommandBaseClass(final CompilationUnit command,
                                               final ClassOrInterfaceDeclaration commandClass) {
        commandClass.addExtendedType("DefaultCommand");
        command.addImport(BASE_OUTPUT_PACKAGE + ".DefaultCommand");

        final MethodDeclaration method = new MethodDeclaration();
        method.setName("run");
        method.setPublic(true);
        method.setType(new VoidType());
        method.addMarkerAnnotation(Override.class);
        method.addParameter(
                new Parameter(EnumSet.of(Modifier.FINAL),
                        new TypeParameter("ClientConfiguration"),
                        new SimpleName("clientConfiguration")));
        commandClass.addMember(method);
        command.addImport(ImportManager.getImport("ClientConfiguration"));
    }

    private static void addCommandFlags(final CompilationUnit command,
                                        final ClassOrInterfaceDeclaration commandClass,
                                        final String clientGroup,
                                        final MethodDeclaration clientMethod) {
        final NodeList<Parameter> parameters = clientMethod.getParameters();

        final List<Parameter> arguments =
                parameters.stream()
                        .filter(parameter -> parameter.isAnnotationPresent("PathParam"))
                        .collect(Collectors.toList());
        if (arguments.size() == 1) {
            final Parameter parameter = arguments.get(0);
            addArgumentFlag(parameter.getType().resolve().describe(), parameter.getNameAsString(), command, commandClass);
        }

        if (arguments.size() > 1) {
            addArgumentsFlag(command, commandClass);
        }

        final List<Parameter> options = new ArrayList<>(parameters);
        options.removeAll(arguments);

        for (final Parameter option : options) {
            ResolvedType resolvedType = option.getType().resolve();
            if (isPrimitiveOrValueOf(resolvedType) || isPrimitiveAndValueOfCollection(resolvedType)) {
                addOptionFlag(resolvedType.describe(), option.getNameAsString(), command, commandClass);
            } else {
                ResolvedReferenceTypeDeclaration typeDeclaration = null;
                boolean isGeneric = option.toString().contains("<");
                if (isGeneric) {
                    typeDeclaration = JavaParserFacade.get(CustomTypeSolver.get())
                            .convertToUsage(option.getType().asClassOrInterfaceType().getTypeArguments().get().iterator().next())
                            .asReferenceType()
                            .getTypeDeclaration();
                } else {
                    typeDeclaration =
                            JavaParserFacade.get(CustomTypeSolver.get())
                                    .getType(option)
                                    .asReferenceType()
                                    .getTypeDeclaration();
                }
                expandParameterReference(typeDeclaration, null, command, commandClass);
                addInstantiation(option.getNameAsString(), typeDeclaration, command, commandClass);
            }
        }
        addRunStatement(command, commandClass, clientGroup, clientMethod);
    }

    private static void expandParameterReference(final ResolvedReferenceTypeDeclaration parameter,
                                                 final String prefix,
                                                 final CompilationUnit command,
                                                 final ClassOrInterfaceDeclaration commandClass) {
        for (final ResolvedFieldDeclaration field : parameter.getAllFields()) {
            if (field.isStatic()) {
                continue;
            }
            final ResolvedType type = field.getType();

            if (isPrimitiveOrValueOf(type) || isPrimitiveAndValueOfCollection(type)) {
                addOptionFlag(type.describe(),
                        isEmpty(prefix) ? field.getName() : prefix + capitalize(field.getName()),
                        command,
                        commandClass);
            } else if (type.isReferenceType()) {
                if (Utils.isCollection(type)) {
                    ResolvedReferenceType resolvedReferenceType = type.asReferenceType().getTypeParametersMap().get(0).b.asReferenceType();
                    ResolvedReferenceTypeDeclaration typeDeclaration = resolvedReferenceType.getTypeDeclaration();
                    expandParameterReference(typeDeclaration, typeDeclaration.getName().toLowerCase(), command, commandClass);
                } else {
                    expandParameterReference(type.asReferenceType().getTypeDeclaration(), field.getName(), command, commandClass);
                }
            }
        }
    }

    private static void addArgumentFlag(final String type,
                                        final String name,
                                        final CompilationUnit command,
                                        final ClassOrInterfaceDeclaration commandClass) {
        final FieldDeclaration flag = commandClass.addField(type, name, Modifier.PRIVATE);

        final NormalAnnotationExpr argumentsAnnotation = new NormalAnnotationExpr();
        argumentsAnnotation.setName("Arguments");
        argumentsAnnotation.addPair("required", "true");
        command.addImport(ImportManager.getImport("Arguments"));
        flag.addAnnotation(argumentsAnnotation);
    }

    private static void addArgumentsFlag(final CompilationUnit command,
                                         final ClassOrInterfaceDeclaration commandClass) {
        final FieldDeclaration flag = commandClass.addField("Collection<String>", "arguments", Modifier.PRIVATE);
        command.addImport(Collection.class);

        final NormalAnnotationExpr argumentsAnnotation = new NormalAnnotationExpr();
        argumentsAnnotation.setName("Arguments");
        argumentsAnnotation.addPair("required", "true");
        command.addImport(ImportManager.getImport("Arguments"));
        flag.addAnnotation(argumentsAnnotation);
    }

    private static void addOptionFlag(final String type,
                                      final String name,
                                      final CompilationUnit command,
                                      final ClassOrInterfaceDeclaration commandClass) {

        boolean isPresent = commandClass.getFields()
                .stream().filter(f -> f.getVariables().get(0).getNameAsString().equals(name))
                .findFirst()
                .isPresent();
        if (isPresent) {
            return;
        }

        final FieldDeclaration flag = commandClass.addField(type, name, Modifier.PRIVATE);

        final NormalAnnotationExpr argumentsAnnotation = new NormalAnnotationExpr();
        argumentsAnnotation.setName("Option");
        argumentsAnnotation.addPair("name", "\"--" + formatCamelCaseTo(name, "-") + "\"");
        command.addImport(ImportManager.getImport("Option"));
        flag.addAnnotation(argumentsAnnotation);
    }

    private static void addInstantiation(String fieldName, final ResolvedReferenceTypeDeclaration parameter,
                                         final CompilationUnit command,
                                         final ClassOrInterfaceDeclaration commandClass) {
        final MethodDeclaration run =
                commandClass.getMethodsByName("run").stream().findFirst().orElseThrow(IllegalArgumentException::new);


        boolean isBuilderClass = Optional.of(parameter.isClass() &&
                parameter instanceof JavaParserClassDeclaration &&
                ((JavaParserClassDeclaration) parameter).getWrappedNode().getAnnotationByName("Builder").isPresent()).orElse(false);

        List<FieldDeclaration> fields = parameter.getAllFields().stream().map(f -> ((JavaParserFieldDeclaration) f).getWrappedNode()).collect(Collectors.toList());

        if (isBuilderClass) {
            final String builder = "final " +
                    parameter.getQualifiedName() +
                    " " +
                    fieldName +
                    " = " +
                    parameter.getQualifiedName() +
                    ".builder()" +
                    readInstanceFields(command, fields, "", fieldName, ModelType.BUILDER) +
                    ".build();";
            run.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(builder));
        } else {
            String call = "final " +
                    parameter.getQualifiedName() +
                    " " +
                    fieldName +
                    " = new " + parameter.getQualifiedName() + "();\n";

            call += readInstanceFields(command, fields, "", fieldName, ModelType.SETTER);
            String[] statements = call.split("\n");
            for (String statement : statements) {
                run.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(statement));
            }

        }
    }

    private static void addRunStatement(CompilationUnit command, final ClassOrInterfaceDeclaration commandClass, String clientGroup, MethodDeclaration clientMethod) {
        command.addImport(Configuration.getClientPackage() + "." + Configuration.clientName);

        List<String> runParams = clientMethod.getParameters().stream().map(p -> {
            if (p.getTypeAsString().startsWith("List")) {
                Type type = p.getType().asClassOrInterfaceType().getTypeArguments().get().stream().findFirst().get();
                if (isPrimitiveOrValueOf(type.resolve())) {
                    return p.getNameAsString();
                } else {
                    ClassOrInterfaceDeclaration clazz = ((JavaParserClassDeclaration) JavaParserFacade.get(CustomTypeSolver.get())
                            .convertToUsage(type)
                            .asReferenceType().getTypeDeclaration())
                            .getWrappedNode();
                    boolean wasGenerated = clazz.getAnnotationByName("Generated") != null;
                    if (wasGenerated) {
                        return "java.util.Arrays.asList(" + p.getNameAsString() + ")";
                    }
                    //TODO: What if the object was not generated and has a List?
                }
            }
            return p.getNameAsString();
        }).collect(Collectors.toList());

        final MethodDeclaration run =
                commandClass.getMethodsByName("run").stream().findFirst().orElseThrow(IllegalArgumentException::new);

        String runCommand = "new " + Configuration.clientName + "(clientConfiguration)." + clientGroup.toLowerCase() + "()."
                + clientMethod.getNameAsString() + "(" + Join.join(",", runParams) + ")";

        if (!clientMethod.getType().isVoidType()) {
            runCommand = "final Object result = " + runCommand + ";";
            run.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(runCommand));
            final String conditionalResponseBody =
                    "if(result != null){\n" +
                        "System.out.println(new org.apache.johnzon.mapper.MapperBuilder().setPretty(true).build()\n" +
                            "                    .writeObjectAsString(result));\n" +
                    "}";
            run.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(conditionalResponseBody));
        } else {
            runCommand += ";";
            run.getBody().get().asBlockStmt().addStatement(JavaParser.parseStatement(runCommand));
        }

    }

    private static boolean isPrimitiveOrValueOf(final ResolvedType type) {
        if (type.isPrimitive()) {
            return true;
        }

        if (type.isReferenceType()) {
            final ResolvedReferenceTypeDeclaration typeDeclaration = type.asReferenceType().getTypeDeclaration();

            if (typeDeclaration.isEnum()) {
                return true;
            } else if (typeDeclaration.getName().equals("String") || typeDeclaration.getName().contains("<String>")) {
                return true;
            } else if (typeDeclaration.getDeclaredMethods()
                    .stream()
                    .filter(method -> method.getName().equals("valueOf"))
                    .anyMatch(method -> method.getNumberOfParams() == 1)) {
                return true;
            } else if (typeDeclaration.isClass()) {
                return typeDeclaration.asClass().getConstructors()
                        .stream()
                        .filter(constructor -> constructor.getNumberOfParams() == 1)
                        .anyMatch(constructor -> constructor.getParam(0).describeType().contains("String"));
            }
        }

        return false;
    }

    private static boolean isPrimitiveAndValueOfCollection(final ResolvedType type) {
        if (type.isReferenceType()) {
            final ResolvedReferenceTypeDeclaration typeDeclaration = type.asReferenceType().getTypeDeclaration();

            if (typeDeclaration.canBeAssignedTo(CustomTypeSolver.get().solveType("java.util.Collection"))) {
                final List<ResolvedType> collectionParameters = type.asReferenceType().typeParametersValues();
                if (collectionParameters.size() == 1) {
                    return isPrimitiveOrValueOf(collectionParameters.get(0));
                }
            }
        }

        return false;
    }

    private static void generateCli(final Map<String, List<String>> groups) throws IOException {
        final CompilationUnit cli = new CompilationUnit(BASE_OUTPUT_PACKAGE);
        cli.setPackageDeclaration(BASE_OUTPUT_PACKAGE);
        cli.addClass("MainCli");

        final ClassOrInterfaceDeclaration cliClass =
                cli.getClassByName("MainCli").orElseThrow(IllegalArgumentException::new);
        cliClass.addConstructor(Modifier.PRIVATE);

        final MethodDeclaration main = new MethodDeclaration();
        main.setPublic(true);
        main.setStatic(true);
        main.setType("void");
        main.setName("main");
        main.addAndGetParameter(String.class, "args").setVarArgs(true);

        final BlockStmt block = new BlockStmt();
        cli.addImport(ImportManager.getImport("Cli"));
        block.addStatement("final Cli.CliBuilder<Runnable> cliBuilder = Cli.builder(\"" + Configuration.cmdLineName + "\");");
        cli.addImport(ImportManager.getImport("Help"));
        block.addStatement("cliBuilder.withDefaultCommand(Help.class);");
        block.addStatement("cliBuilder.withCommand(Help.class);");

        main.setBody(block);
        cliClass.addMember(main);

        for (final String group : groups.keySet()) {
            final StringBuilder groupCommand = new StringBuilder();
            groupCommand.append("cliBuilder.withGroup(\"").append(formatCamelCaseTo(group, "-")).append("\")");
            groupCommand.append(".withDefaultCommand(Help.class)");
            final List<String> commands = groups.get(group);
            for (final String command : commands) {
                groupCommand.append(".withCommand(").append(command).append(".class").append(")");
                cli.addImport(Configuration.resourcePackage + ".cmd." + command);
            }
            groupCommand.append(";");
            block.addStatement(groupCommand.toString());
        }

        block.addStatement("final Cli<Runnable> cli = cliBuilder.build();");
        StringBuilder runBlock = new StringBuilder();
        runBlock.append("try { ");
        runBlock.append("cli.parse(args).run();");
        runBlock.append("} catch (Exception e) { ");
        runBlock.append("System.out.println(\"ERROR\");");
        runBlock.append("System.out.println(e.getMessage());");
        runBlock.append("}");
        block.addStatement(runBlock.toString());
        Utils.save("MainCli.java", BASE_OUTPUT_PACKAGE, cli.toString());
    }

    private static void save(final String className, final CompilationUnit classToBeSaved) {
        if (classToBeSaved == null) {
            return;
        }

        final String modified =
                Stream.of(classToBeSaved.toString())
                        .map(RemoveDuplicateImports::apply)
                        .map(Reformat::apply)
                        .map(RemoveUnusedImports::removeUnusedImports)
                        .findFirst()
                        .orElseThrow(IllegalStateException::new);

        try {
            Utils.save(className + ".java", Configuration.resourcePackage + ".cmd", modified);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private static String readInstanceFields(final CompilationUnit rootClassUnit,
                                             final List<FieldDeclaration> fields, final String prefix,
                                             final String classFieldName, final ModelType modelType) {
        return fields
                .stream()
                .filter(f -> !f.isStatic())
                .map(fieldDeclaration -> readFieldOrUnflattenClass(rootClassUnit, fieldDeclaration, prefix, classFieldName, modelType))
                .collect(Collectors.joining());
    }

    private static String readFieldOrUnflattenClass(final CompilationUnit rootClassUnit,
                                                    final FieldDeclaration field, final String prefix,
                                                    final String classFieldName, final ModelType modelType) {
        if (Utils.isWrapperOrPrimitiveOrDate(field)) {
            return readField(field, prefix, classFieldName, modelType);
        } else {
            return unFlattenClass(rootClassUnit, field, prefix, classFieldName, modelType);
        }
    }

    private static String unFlattenClass(final CompilationUnit rootClassUnit,
                                         final FieldDeclaration field, final String prefix,
                                         final String classFieldName, final ModelType modelType) {
        ResolvedReferenceTypeDeclaration resolvedType = null;


        if (Utils.isCollection(field.getElementType().resolve())) {
            resolvedType = field.getElementType().resolve()
                    .asReferenceType()
                    .getTypeParametersMap()
                    .get(0).b
                    .asReferenceType()
                    .getTypeDeclaration();
        } else {
            resolvedType = JavaParserFacade.get(CustomTypeSolver.get())
                    .getType(field.getVariables().get(0))
                    .asReferenceType()
                    .getTypeDeclaration();
        }

        if (resolvedType.isEnum()) {
            return readField(field, prefix, classFieldName, modelType);
        } else {
            final List<ResolvedFieldDeclaration> allFields = resolvedType.getAllFields();

            final List<FieldDeclaration> fieldsToBeExpanded =
                    allFields.stream()
                            .filter(f -> f instanceof JavaParserFieldDeclaration)
                            .filter(f -> ((JavaParserFieldDeclaration) f).getWrappedNode() != null)
                            .map(f -> ((JavaParserFieldDeclaration) f).getWrappedNode())
                            .filter(f -> !f.getAnnotationByName("Model").isPresent() ||
                                    !Utils.hasOperations(f))
                            .collect(Collectors.toList());

            if (fieldsToBeExpanded.isEmpty()) {
                return "";
            }

            final String objectClass;
            final int model = resolvedType.getName().indexOf("Model");
            if (model == -1) {
                objectClass = resolvedType.getName();
                rootClassUnit.addImport(resolvedType.getQualifiedName());
            } else {
                // TODO Fix this
                objectClass = "CREATE".contains("CREATE") ?
                        "Create" + resolvedType.getName().substring(0, model) :
                        "Update" + resolvedType.getName().substring(0, model);
                rootClassUnit.addImport(resolvedType.getPackageName() + "." + objectClass);
            }

            if (modelType == ModelType.BUILDER) {
                return "." + field.getVariables().get(0) +
                        "(" +
                        objectClass +
                        ".builder()" +
                        readInstanceFields(rootClassUnit, fieldsToBeExpanded, field.getVariables().get(0).getNameAsString(), classFieldName, ModelType.BUILDER) +
                        ".build()" +
                        ")";
            } else {
                VariableDeclarator variable = field.getVariables().get(0);
                String statements = "final " + resolvedType.getClassName() + " " + resolvedType.getClassName().toLowerCase() + " = new " + resolvedType.getClassName() + "();\n";

                if (variable.getTypeAsString().contains("List<")) {
                    statements += readInstanceFields(rootClassUnit, fieldsToBeExpanded,
                            resolvedType.getClassName().toLowerCase(), resolvedType.getClassName().toLowerCase(), ModelType.SETTER);
                    statements += classFieldName + ".set" + WordUtils.capitalize(variable.getNameAsString()) +
                            "(java.util.Arrays.asList(" + resolvedType.getClassName().toLowerCase() + "));\n";
                } else {
                    statements += readInstanceFields(rootClassUnit, fieldsToBeExpanded,
                            variable.getNameAsString(), variable.getNameAsString(), ModelType.SETTER);
                    statements += classFieldName + ".set" + WordUtils.capitalize(variable.getNameAsString()) +
                            "(" + variable.getNameAsString() + ");\n";
                }
                return statements;
            }

        }
    }

    private static String readField(final FieldDeclaration field, final String prefix, final String classFieldName, final ModelType modelType) {
        FieldDeclaration newField = field.clone();
        if (!newField.getAnnotationByName("Model").isPresent() ||
                !Utils.hasOperations(newField)) {
            final String fieldName = field.getVariables().get(0).getNameAsString();
            final String readFieldName = "".equals(prefix) ? fieldName : prefix + WordUtils.capitalize(fieldName);

            if (modelType == ModelType.BUILDER) {
                return "." + fieldName + "( " + readFieldName + ")";
            } else {
                return classFieldName + ".set" + WordUtils.capitalize(fieldName) + "(" + readFieldName + ");\n";
            }
        }

        return "";
    }

    private static void writeFieldOrFlattenClass(CompilationUnit rootClassUnit, String operation, String classPrefix,
                                                 ClassOrInterfaceDeclaration newClass, FieldDeclaration f, String objectName,
                                                 String pkg, String clazzName) {
        if (Utils.isWrapperOrPrimitiveOrDate(f)) {
            writeField(operation, rootClassUnit, newClass, f, objectName, pkg, clazzName, false);
        } else {
            resolveFieldClass(rootClassUnit, operation, classPrefix, newClass, f, objectName);
        }
    }

    private static void resolveFieldClass(CompilationUnit rootClassUnit, String operation, String classPrefix,
                                          ClassOrInterfaceDeclaration newClass, FieldDeclaration f, String objectName) {
        VariableDeclarator type = f.getVariables().stream().findFirst().get();
        ResolvedReferenceTypeDeclaration solvedType;
        try {
            solvedType = JavaParserFacade.get(CustomTypeSolver.get())
                    .getType(type)
                    .asReferenceType()
                    .getTypeDeclaration();

        } catch (RuntimeException e) {
            return;  // TODO: Arrays with types - skip for now
        }

        if (solvedType.isEnum()) {
            writeField(operation, rootClassUnit, newClass, f, objectName, solvedType.getPackageName(), solvedType.getClassName(), true);
        } else {
            flattenClassFields(rootClassUnit, operation, classPrefix, newClass, type, solvedType, solvedType.getPackageName(), solvedType.getClassName());
        }
    }

    private static void flattenClassFields(CompilationUnit rootClassUnit, String operation, String classPrefix,
                                           ClassOrInterfaceDeclaration newClass, VariableDeclarator type,
                                           ResolvedReferenceTypeDeclaration solvedType, String pkg, String clazzName) {
        List<ResolvedFieldDeclaration> allFields = solvedType.getAllFields();

        List<FieldDeclaration> fieldsToBeExpanded = allFields.stream()
                .filter(field -> field instanceof JavaParserFieldDeclaration)
                .filter(field -> ((JavaParserFieldDeclaration) field).getWrappedNode() != null)
                .map(field -> ((JavaParserFieldDeclaration) field).getWrappedNode())
                .collect(Collectors.toList());

        fieldsToBeExpanded.forEach(field -> {
            if (!field.getAnnotationByName("Model").isPresent()
                    || !Utils.hasOperations(field) ||
                    Utils.isOperationPresent(field, operation)) {
                writeFieldOrFlattenClass(rootClassUnit, operation, classPrefix, newClass, field, type.getNameAsString(), pkg, clazzName);
            }
        });
    }

    private static void handleId(FieldDeclaration f, CompilationUnit rootClassUnit, ClassOrInterfaceDeclaration newClass) {
        FieldDeclaration newField = f.clone();
        newClass.addMember(newField);
        newField.setAnnotations(new NodeList<>());
        newField.setFinal(false);
        addCommand(f, newField, rootClassUnit, true);
    }

    private static void writeField(String operation, CompilationUnit rootClassUnit,
                                   ClassOrInterfaceDeclaration newClass, FieldDeclaration f, String objectCommandName,
                                   String pkg, String clazzName, boolean isEnum) {
        FieldDeclaration newField = f.clone();
        if (!newField.getAnnotationByName("Model").isPresent()
                || !Utils.hasOperations(newField) ||
                Utils.isOperationPresent(newField, operation)) {
            CompilationUnit newClassCompilationUnit = newClass.findCompilationUnit().get();
            newClass.addMember(newField);
            newField.setAnnotations(new NodeList<>());
            newField.setFinal(false);

            if (objectCommandName != null) {
                VariableDeclarator varNewField = newField.getVariables().stream().findFirst().get();
                // Change name to have the object before the field name
                varNewField.setName(WordUtils.uncapitalize(objectCommandName) + WordUtils.capitalize(varNewField.getNameAsString()));
                addTypeInfoAnnotation(pkg, clazzName, isEnum, newField, newClassCompilationUnit);
            }

            addCommand(f, newField, rootClassUnit, false);
            if (pkg != null && clazzName != null) {
                newClassCompilationUnit.addImport(pkg + "." + clazzName);
            }
        } else if (Utils.isId(newField) && Objects.equals(operation, Operation.UPDATE)) {
            handleId(newField, rootClassUnit, newClass);
        }
    }

    private static void addTypeInfoAnnotation(String pkg, String clazzName, boolean isEnum, FieldDeclaration newField, CompilationUnit newClassCompilationUnit) {
        NormalAnnotationExpr typeInfo = new NormalAnnotationExpr();
        typeInfo.setName("TypeInfo");
        typeInfo.addPair("value", "\"" + pkg + "-" + clazzName + "\"");
        if (isEnum) {
            typeInfo.addPair("isEnum", "true");
        }
        newClassCompilationUnit.addImport("org.tomitribe.api.TypeInfo");
        newField.addAnnotation(typeInfo);
    }

    private static void addCommand(FieldDeclaration oldField, FieldDeclaration field, CompilationUnit unit, boolean id) {
        String fieldName = field.getVariables().stream().findFirst().get().getNameAsString();
        StringBuilder annotation = new StringBuilder();
        boolean required = getRequired(oldField);
        if (id) {
            field.addAnnotation(JavaParser.parseAnnotation("@Arguments(required = " + id + ")"));
            unit.addImport(ImportManager.getImport("Arguments"));
        } else {
            annotation.append("@Option(name = {\"--").append(formatCamelCaseTo(fieldName, "-")).append("\"}");
            if (required) {
                annotation.append(", required = true ");
            }
            annotation.append(")");
            field.addAnnotation(JavaParser.parseAnnotation(annotation.toString()));
            unit.addImport(ImportManager.getImport("Option"));
        }
    }

    private static boolean getRequired(FieldDeclaration field) {
        Optional<AnnotationExpr> schema = field.getAnnotationByName("Schema");
        if (schema.isPresent()) {
            AnnotationExpr ann = schema.get();
            if (ann.isNormalAnnotationExpr()) {
                Map<String, MemberValuePair> pairs = Utils.pairs(schema.get().asNormalAnnotationExpr());
                MemberValuePair valuePair = pairs.get("required");
                if (valuePair != null) {
                    return valuePair.getValue().asBooleanLiteralExpr().getValue();
                }
            }
        }
        return false;
    }
}
