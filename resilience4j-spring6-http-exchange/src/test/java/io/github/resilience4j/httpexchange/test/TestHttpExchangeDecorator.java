/*
 *
 * Copyright 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 *
 */
package io.github.resilience4j.httpexchange.test;

import io.github.resilience4j.core.functions.CheckedFunction;
import io.github.resilience4j.httpexchange.HttpExchangeDecorator;
import io.github.resilience4j.httpexchange.HttpExchangeTarget;

import java.lang.reflect.Method;

/**
 * Test decorator for unit tests that tracks whether it has been called
 * and optionally provides an alternative function.
 */
public class TestHttpExchangeDecorator implements HttpExchangeDecorator {

    private boolean called = false;
    private CheckedFunction<Object[], Object> alternativeFunction;

    @Override
    public CheckedFunction<Object[], Object> decorate(CheckedFunction<Object[], Object> fn,
                                                       Method method,
                                                       HttpExchangeTarget<?> target) {
        return args -> {
            called = true;
            if (alternativeFunction != null) {
                return alternativeFunction.apply(args);
            }
            return fn.apply(args);
        };
    }

    public boolean isCalled() {
        return called;
    }

    public void setAlternativeFunction(CheckedFunction<Object[], Object> alternativeFunction) {
        this.alternativeFunction = alternativeFunction;
    }

    public void reset() {
        this.called = false;
        this.alternativeFunction = null;
    }
}
