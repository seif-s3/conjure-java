/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.undertow.processor.generate;

import com.google.common.collect.ImmutableList;
import com.palantir.conjure.java.undertow.annotations.BearerTokenCookieDeserializer;
import com.palantir.conjure.java.undertow.annotations.CookieDeserializer;
import com.palantir.conjure.java.undertow.annotations.HeaderParamDeserializer;
import com.palantir.conjure.java.undertow.annotations.PathParamDeserializer;
import com.palantir.conjure.java.undertow.annotations.QueryParamDeserializer;
import com.palantir.conjure.java.undertow.lib.Deserializer;
import com.palantir.conjure.java.undertow.lib.Endpoint;
import com.palantir.conjure.java.undertow.lib.ReturnValueWriter;
import com.palantir.conjure.java.undertow.lib.Serializer;
import com.palantir.conjure.java.undertow.lib.TypeMarker;
import com.palantir.conjure.java.undertow.lib.UndertowRuntime;
import com.palantir.conjure.java.undertow.lib.UndertowService;
import com.palantir.conjure.java.undertow.processor.data.EndpointDefinition;
import com.palantir.conjure.java.undertow.processor.data.EndpointName;
import com.palantir.conjure.java.undertow.processor.data.ParameterType;
import com.palantir.conjure.java.undertow.processor.data.ParameterType.Cases;
import com.palantir.conjure.java.undertow.processor.data.ReturnType;
import com.palantir.conjure.java.undertow.processor.data.ServiceDefinition;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import org.immutables.value.Value;

public final class ConjureUndertowEndpointsGenerator {

    private static final String DELEGATE_NAME = "delegate";
    private static final String RUNTIME_NAME = "runtime";
    private static final String EXCHANGE_NAME = "exchange";
    private static final String RETURN_VALUE = "returnValue";

    private final ServiceDefinition serviceDefinition;

    public ConjureUndertowEndpointsGenerator(ServiceDefinition serviceDefinition) {
        this.serviceDefinition = serviceDefinition;
    }

    public TypeSpec generate() {
        FieldSpec delegate = FieldSpec.builder(serviceDefinition.serviceInterface(), DELEGATE_NAME)
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();
        ImmutableList<TypeSpec> endpoints = serviceDefinition.endpoints().stream()
                .map(endpoint -> endpoint(serviceDefinition, endpoint))
                .collect(ImmutableList.toImmutableList());
        return TypeSpec.classBuilder(serviceDefinition.undertowService())
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", getClass().getCanonicalName())
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ClassName.get(UndertowService.class))
                .addField(delegate)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(serviceDefinition.serviceInterface(), DELEGATE_NAME)
                        .addStatement("this.$N = $N", delegate, DELEGATE_NAME)
                        .build())
                .addMethod(MethodSpec.methodBuilder("of")
                        .addParameter(serviceDefinition.serviceInterface(), DELEGATE_NAME)
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addStatement("return new $T($N)", serviceDefinition.undertowService(), DELEGATE_NAME)
                        .returns(UndertowService.class)
                        .build())
                .addMethod(MethodSpec.methodBuilder("endpoints")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .addParameter(UndertowRuntime.class, serviceDefinition.conjureRuntimeArgName())
                        .returns(ParameterizedTypeName.get(List.class, Endpoint.class))
                        .addStatement(
                                "return $T.of($L)",
                                ImmutableList.class,
                                endpoints.stream()
                                        .map(endpoint -> CodeBlock.of(
                                                "new $N($N, $N)", endpoint.name, RUNTIME_NAME, DELEGATE_NAME))
                                        .collect(CodeBlock.joining(", ")))
                        .build())
                .addTypes(endpoints)
                .build();
    }

    private static String endpointClassName(EndpointName endpointName) {
        String name = endpointName.get();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Endpoint";
    }

    @SuppressWarnings("checkstyle:MethodLength") // TODO(ckozak): refactor
    private static TypeSpec endpoint(ServiceDefinition service, EndpointDefinition endpoint) {
        List<AdditionalField> additionalFields = new ArrayList<>();
        MethodSpec.Builder handlerBuilder = MethodSpec.methodBuilder("handleRequest")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(HttpServerExchange.class, EXCHANGE_NAME)
                .addException(Exception.class);
        ReturnType returnType = endpoint.returns();
        TypeName responseTypeName =
                returnType.asyncInnerType().orElseGet(returnType::returnType).box();
        if (returnType.asyncInnerType().isEmpty() && returnType.isVoid()) {
            handlerBuilder
                    .addStatement(invokeDelegate(endpoint))
                    .addStatement("$N.setStatusCode($T.NO_CONTENT)", EXCHANGE_NAME, StatusCodes.class);
        } else {
            additionalFields.add(ImmutableAdditionalField.builder()
                    .field(FieldSpec.builder(
                                    ParameterizedTypeName.get(ClassName.get(Serializer.class), responseTypeName),
                                    returnType.serializerFieldName(),
                                    Modifier.PRIVATE,
                                    Modifier.FINAL)
                            .build())
                    .constructorInitializer(CodeBlock.builder()
                            .addStatement(
                                    "this.$N = $L.serializer(new $T<$T>() {}, $N)",
                                    returnType.serializerFieldName(),
                                    returnType.serializerFactory(),
                                    TypeMarker.class,
                                    responseTypeName,
                                    RUNTIME_NAME)
                            .build())
                    .build());
            if (returnType.asyncInnerType().isPresent()) {
                handlerBuilder.addStatement(
                        "$N.async().register($L, this, $N)", RUNTIME_NAME, invokeDelegate(endpoint), EXCHANGE_NAME);
            } else {
                handlerBuilder.addStatement("write($L, $N)", invokeDelegate(endpoint), EXCHANGE_NAME);
            }
        }
        endpoint.arguments().forEach(def -> def.paramType().match(new Cases<Void>() {
            @Override
            public Void body(CodeBlock deserializerFactory, String deserializerFieldName) {
                TypeName requestBodyType = def.argType().match(ArgTypeTypeName.INSTANCE);
                additionalFields.add(ImmutableAdditionalField.builder()
                        .field(FieldSpec.builder(
                                        ParameterizedTypeName.get(ClassName.get(Deserializer.class), requestBodyType),
                                        deserializerFieldName,
                                        Modifier.PRIVATE,
                                        Modifier.FINAL)
                                .build())
                        .constructorInitializer(CodeBlock.builder()
                                .addStatement(
                                        "this.$N = $L.deserializer(new $T<$T>() {}, $N)",
                                        deserializerFieldName,
                                        deserializerFactory,
                                        TypeMarker.class,
                                        requestBodyType,
                                        RUNTIME_NAME)
                                .build())
                        .build());
                return null;
            }

            @Override
            public Void header(String headerName, String deserializerFieldName, CodeBlock deserializerFactory) {
                TypeName paramType = def.argType().match(ArgTypeTypeName.INSTANCE);
                additionalFields.add(ImmutableAdditionalField.builder()
                        .field(FieldSpec.builder(
                                        ParameterizedTypeName.get(ClassName.get(Deserializer.class), paramType.box()),
                                        deserializerFieldName,
                                        Modifier.PRIVATE,
                                        Modifier.FINAL)
                                .build())
                        .constructorInitializer(CodeBlock.builder()
                                .addStatement(
                                        "this.$N = new $T<>($S, $L)",
                                        deserializerFieldName,
                                        HeaderParamDeserializer.class,
                                        headerName,
                                        deserializerFactory)
                                .build())
                        .build());
                return null;
            }

            @Override
            public Void path(String paramName, String deserializerFieldName, CodeBlock deserializerFactory) {
                TypeName paramType = def.argType().match(ArgTypeTypeName.INSTANCE);
                additionalFields.add(ImmutableAdditionalField.builder()
                        .field(FieldSpec.builder(
                                        ParameterizedTypeName.get(ClassName.get(Deserializer.class), paramType.box()),
                                        deserializerFieldName,
                                        Modifier.PRIVATE,
                                        Modifier.FINAL)
                                .build())
                        .constructorInitializer(CodeBlock.builder()
                                .addStatement(
                                        "this.$N = new $T<>($S, $L)",
                                        deserializerFieldName,
                                        PathParamDeserializer.class,
                                        paramName,
                                        deserializerFactory)
                                .build())
                        .build());
                return null;
            }

            @Override
            public Void query(String paramName, String deserializerFieldName, CodeBlock deserializerFactory) {
                TypeName paramType = def.argType().match(ArgTypeTypeName.INSTANCE);
                additionalFields.add(ImmutableAdditionalField.builder()
                        .field(FieldSpec.builder(
                                        ParameterizedTypeName.get(ClassName.get(Deserializer.class), paramType.box()),
                                        deserializerFieldName,
                                        Modifier.PRIVATE,
                                        Modifier.FINAL)
                                .build())
                        .constructorInitializer(CodeBlock.builder()
                                .addStatement(
                                        "this.$N = new $T<>($S, $L)",
                                        deserializerFieldName,
                                        QueryParamDeserializer.class,
                                        paramName,
                                        deserializerFactory)
                                .build())
                        .build());
                return null;
            }

            @Override
            public Void cookie(String cookieName, String deserializerFieldName, CodeBlock deserializerFactory) {
                TypeName paramType = def.argType().match(ArgTypeTypeName.INSTANCE);
                additionalFields.add(ImmutableAdditionalField.builder()
                        .field(FieldSpec.builder(
                                        ParameterizedTypeName.get(ClassName.get(Deserializer.class), paramType.box()),
                                        deserializerFieldName,
                                        Modifier.PRIVATE,
                                        Modifier.FINAL)
                                .build())
                        .constructorInitializer(CodeBlock.builder()
                                .addStatement(
                                        "this.$N = new $T<>($S, $L)",
                                        deserializerFieldName,
                                        CookieDeserializer.class,
                                        cookieName,
                                        deserializerFactory)
                                .build())
                        .build());
                return null;
            }

            @Override
            public Void authCookie(String cookieName, String deserializerFieldName) {
                TypeName paramType = def.argType().match(ArgTypeTypeName.INSTANCE);
                additionalFields.add(ImmutableAdditionalField.builder()
                        .field(FieldSpec.builder(
                                        ParameterizedTypeName.get(ClassName.get(Deserializer.class), paramType.box()),
                                        deserializerFieldName,
                                        Modifier.PRIVATE,
                                        Modifier.FINAL)
                                .build())
                        .constructorInitializer(CodeBlock.builder()
                                .addStatement(
                                        "this.$N = new $T(runtime, $S)",
                                        deserializerFieldName,
                                        BearerTokenCookieDeserializer.class,
                                        cookieName)
                                .build())
                        .build());
                return null;
            }

            @Override
            public Void authHeader() {
                return null;
            }

            @Override
            public Void exchange() {
                return null;
            }

            @Override
            public Void context() {
                return null;
            }
        }));

        TypeSpec.Builder endpointBuilder = TypeSpec.classBuilder(endpointClassName(endpoint.endpointName()))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(HttpHandler.class)
                .addSuperinterface(Endpoint.class)
                .addField(UndertowRuntime.class, RUNTIME_NAME, Modifier.PRIVATE, Modifier.FINAL)
                .addField(service.serviceInterface(), DELEGATE_NAME, Modifier.PRIVATE, Modifier.FINAL)
                .addFields(
                        additionalFields.stream().map(AdditionalField::field).collect(ImmutableList.toImmutableList()))
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(UndertowRuntime.class, RUNTIME_NAME)
                        .addParameter(service.serviceInterface(), DELEGATE_NAME)
                        .addStatement("this.$1N = $1N", RUNTIME_NAME)
                        .addStatement("this.$1N = $1N", DELEGATE_NAME)
                        .addCode(additionalFields.stream()
                                .map(AdditionalField::constructorInitializer)
                                .collect(CodeBlock.joining("")))
                        .build())
                .addMethod(handlerBuilder.build());

        if (!TypeName.VOID.equals(returnType.returnType())) {
            endpointBuilder.addSuperinterface(
                    ParameterizedTypeName.get(ClassName.get(ReturnValueWriter.class), responseTypeName));
            endpointBuilder.addMethod(MethodSpec.methodBuilder("write")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addParameter(responseTypeName, RETURN_VALUE)
                    .addParameter(HttpServerExchange.class, EXCHANGE_NAME)
                    .addException(IOException.class)
                    .addStatement(
                            "this.$N.serialize($N, $N)", returnType.serializerFieldName(), RETURN_VALUE, EXCHANGE_NAME)
                    .build());
        }

        return endpointBuilder
                .addMethod(MethodSpec.methodBuilder("method")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(HttpString.class)
                        .addStatement(
                                "return $T.$N",
                                Methods.class,
                                endpoint.httpMethod().name())
                        .build())
                .addMethod(MethodSpec.methodBuilder("template")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(String.class)
                        .addStatement("return $S", endpoint.httpPath().path())
                        .build())
                .addMethod(MethodSpec.methodBuilder("serviceName")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(String.class)
                        .addStatement("return $S", endpoint.serviceName().get())
                        .build())
                .addMethod(MethodSpec.methodBuilder("name")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(String.class)
                        .addStatement("return $S", endpoint.endpointName().get())
                        .build())
                .addMethod(MethodSpec.methodBuilder("handler")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(HttpHandler.class)
                        .addStatement("return this")
                        .build())
                .build();
    }

    private static CodeBlock invokeDelegate(EndpointDefinition endpoint) {
        CodeBlock args = endpoint.arguments().stream()
                .map(arg -> arg.paramType().match(new ParameterType.Cases<CodeBlock>() {
                    @Override
                    public CodeBlock body(CodeBlock _deserializerFactory, String deserializerFieldName) {
                        return CodeBlock.of("$N.deserialize($N)", deserializerFieldName, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock header(
                            String _headerName, String deserializerFieldName, CodeBlock _deserializerFactory) {
                        return CodeBlock.of("this.$N.deserialize($N)", deserializerFieldName, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock path(
                            String _paramName, String deserializerFieldName, CodeBlock _deserializerFactory) {
                        return CodeBlock.of("this.$N.deserialize($N)", deserializerFieldName, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock query(
                            String _paramName, String deserializerFieldName, CodeBlock _deserializerFactory) {
                        return CodeBlock.of("this.$N.deserialize($N)", deserializerFieldName, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock cookie(
                            String _cookieName, String deserializerFieldName, CodeBlock _deserializerFactory) {
                        return CodeBlock.of("this.$N.deserialize($N)", deserializerFieldName, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock authCookie(String _cookieName, String deserializerFieldName) {
                        return CodeBlock.of("this.$N.deserialize($N)", deserializerFieldName, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock authHeader() {
                        return CodeBlock.of("this.$N.auth().header($N)", RUNTIME_NAME, EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock exchange() {
                        return CodeBlock.of("$N", EXCHANGE_NAME);
                    }

                    @Override
                    public CodeBlock context() {
                        return CodeBlock.of("this.$N.contexts().createContext($N, this)", RUNTIME_NAME, EXCHANGE_NAME);
                    }
                }))
                .collect(CodeBlock.joining(","));
        return CodeBlock.of(
                "this.$N.$N($L)", DELEGATE_NAME, endpoint.endpointName().get(), args);
    }

    @Value.Immutable
    interface AdditionalField {
        FieldSpec field();

        CodeBlock constructorInitializer();
    }
}
