package com.qualflare.testng;

import org.testng.ITestResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * ITestResult has a large surface and TestNG offers no public builder, so the unit tests
 * drive a dynamic proxy answering only the handful of methods the reporter calls. This is
 * deliberately thin: anything the production code starts calling will show up here as an
 * UnsupportedOperationException rather than a silent null.
 */
final class Fakes {

    private Fakes() {}

    static ITestResult result(String className, String methodName, Object[] params) {
        return result(className, methodName, params, ITestResult.SUCCESS, null, false);
    }

    static ITestResult result(String className, String methodName, Object[] params,
                              int status, Throwable thrown, boolean wasRetried) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getParameters":  return params;
                case "getStatus":      return status;
                case "getThrowable":   return thrown;
                case "wasRetried":     return wasRetried;
                case "getName":        return methodName;
                case "getTestClass":   return testClass(className);
                case "getMethod":      return testMethod(methodName);
                case "toString":       return className + "#" + methodName;
                case "hashCode":       return System.identityHashCode(proxy);
                case "equals":         return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "Fakes does not answer ITestResult." + method.getName()
                                    + " -- add it deliberately");
            }
        };
        return (ITestResult) Proxy.newProxyInstance(
                Fakes.class.getClassLoader(), new Class<?>[] {ITestResult.class}, h);
    }

    private static Object testClass(String className) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("getName".equals(method.getName())) return className;
            if ("toString".equals(method.getName())) return className;
            if ("hashCode".equals(method.getName())) return className.hashCode();
            if ("equals".equals(method.getName())) return proxy == args[0];
            throw new UnsupportedOperationException("ITestClass." + method.getName());
        };
        return Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {org.testng.ITestClass.class}, h);
    }

    private static Object testMethod(String methodName) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("getMethodName".equals(method.getName())) return methodName;
            if ("toString".equals(method.getName())) return methodName;
            if ("hashCode".equals(method.getName())) return methodName.hashCode();
            if ("equals".equals(method.getName())) return proxy == args[0];
            throw new UnsupportedOperationException("ITestNGMethod." + method.getName());
        };
        return Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {org.testng.ITestNGMethod.class}, h);
    }
}
