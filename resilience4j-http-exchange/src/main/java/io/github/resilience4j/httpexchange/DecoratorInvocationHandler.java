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
package io.github.resilience4j.httpexchange;

import io.github.resilience4j.core.functions.CheckedFunction;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An instance of {@link InvocationHandler} that uses {@link HttpExchangeDecorator}s to enhance the
 * invocations of methods.
 */
class DecoratorInvocationHandler implements InvocationHandler {

    private final HttpExchangeTarget<?> target;
    private final Object delegate;
    private final Map<Method, CheckedFunction<Object[], Object>> decoratedDispatch;

    public DecoratorInvocationHandler(HttpExchangeTarget<?> target,
                                      Object delegate,
                                      HttpExchangeDecorator invocationDecorator) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(invocationDecorator, "invocationDecorator must not be null");
        this.decoratedDispatch = decorateMethodHandlers(target, delegate, invocationDecorator);
    }

    /**
     * Applies the specified {@link HttpExchangeDecorator} to all methods and returns the result as
     * a map of {@link CheckedFunction}s. Invoking a {@link CheckedFunction} will therefore invoke
     * the decorator which, in turn, may invoke the corresponding method on the delegate.
     *
     * @param target              the target HttpExchange interface metadata.
     * @param delegate            the Spring-created proxy to delegate calls to.
     * @param invocationDecorator the {@link HttpExchangeDecorator} with which to decorate methods.
     * @return a new map where the methods are decorated with the {@link HttpExchangeDecorator}.
     */
    private Map<Method, CheckedFunction<Object[], Object>> decorateMethodHandlers(
        HttpExchangeTarget<?> target,
        Object delegate,
        HttpExchangeDecorator invocationDecorator) {

        final Map<Method, CheckedFunction<Object[], Object>> map = new HashMap<>();

        for (Method method : target.type().getMethods()) {
            // Skip Object methods - they are handled separately in invoke()
            if (isObjectMethod(method)) {
                continue;
            }

            // Create the base invocation function
            CheckedFunction<Object[], Object> invocation = args -> {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };

            // Apply decoration
            CheckedFunction<Object[], Object> decorated =
                invocationDecorator.decorate(invocation, method, target);
            map.put(method, decorated);
        }

        return map;
    }

    private boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args)
        throws Throwable {
        switch (method.getName()) {
            case "equals":
                return equals(args != null && args.length > 0 ? args[0] : null);

            case "hashCode":
                return hashCode();

            case "toString":
                return toString();

            default:
                break;
        }

        // Handle default interface methods by invoking them directly
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        // Execute decorated invocation
        CheckedFunction<Object[], Object> decorated = decoratedDispatch.get(method);
        if (decorated != null) {
            return decorated.apply(args);
        }

        // Fallback to direct delegation (should not normally reach here)
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    public boolean equals(Object obj) {
        Object compareTo = obj;
        if (compareTo == null) {
            return false;
        }
        if (Proxy.isProxyClass(compareTo.getClass())) {
            compareTo = Proxy.getInvocationHandler(compareTo);
        }
        if (compareTo instanceof DecoratorInvocationHandler other) {
            return target.equals(other.target);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return target.hashCode();
    }

    @Override
    public String toString() {
        return target.toString();
    }
}
