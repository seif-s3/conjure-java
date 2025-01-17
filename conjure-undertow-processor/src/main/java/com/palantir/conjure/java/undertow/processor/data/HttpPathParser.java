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

package com.palantir.conjure.java.undertow.processor.data;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import org.glassfish.jersey.uri.internal.UriTemplateParser;

public final class HttpPathParser {

    private final ResolverContext context;

    public HttpPathParser(ResolverContext context) {
        this.context = context;
    }

    public Optional<HttpPath> getHttpPath(Element element, AnnotationReflector requestAnnotation) {
        try {
            String rawPath = requestAnnotation.getAnnotationValue("path", String.class);
            UriTemplateParser uriTemplateParser = new UriTemplateParser(rawPath);

            Splitter splitter = Splitter.on('/');
            String normalized = uriTemplateParser.getNormalizedTemplate();
            Iterable<String> rawSegments = splitter.split(normalized);

            List<HttpPathSegment> pathSegments = new ArrayList<>();
            for (String segment : rawSegments) {
                if (segment.isEmpty()) {
                    continue; // avoid empty segments; typically the first segment is empty
                }

                if (segment.startsWith("{") && segment.endsWith("}")) {
                    pathSegments.add(HttpPathSegments.variable(segment.substring(1, segment.length() - 1)));
                } else {
                    pathSegments.add(HttpPathSegments.fixed(segment));
                }
            }

            return Optional.of(ImmutableHttpPath.builder()
                    .path(rawPath)
                    .addAllSegments(pathSegments)
                    .build());
        } catch (IllegalArgumentException e) {
            context.reportError("Failed to parse http path", element, e);
            return Optional.empty();
        }
    }
}
