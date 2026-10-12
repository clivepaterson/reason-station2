package org.reasonstation.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Aspect
@Component
public class SqlQueryLoggingAspect {

    private static final Logger logger = LoggerFactory.getLogger(SqlQueryLoggingAspect.class);

    @Around("execution(* org.reasonstation.repository..*(..))")
    public Object logSqlQuery(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Query query = method.getAnnotation(Query.class);

        if (logger.isDebugEnabled()) {
            String sql = query == null ? "<derived query>" : query.value();
            logger.debug("{}({}) called by {} SQL: \n{}",
                    method.getName(),
                    formatParameters(method, joinPoint.getArgs()),
                    findCallingMethod(),
                    sql);
        }

        return joinPoint.proceed();
    }

    private String findCallingMethod() {
        Optional<StackWalker.StackFrame> caller = StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(frame -> !isInfrastructureFrame(frame.getClassName()))
                        .findFirst());

        return caller.map(frame -> frame.getClassName() + "." + frame.getMethodName())
                .orElse("<unknown>");
    }

    private boolean isInfrastructureFrame(String className) {
        return className.equals(SqlQueryLoggingAspect.class.getName())
                || className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("org.springframework.")
                || className.startsWith("org.aspectj.")
                || className.startsWith("org.reasonstation.repository.")
                || className.contains("$$");
    }

    private String formatParameters(Method method, Object[] arguments) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        IntStream.range(0, arguments.length).forEach(index -> {
            Param parameter = method.getParameters()[index].getAnnotation(Param.class);
            String name = parameter == null ? "arg" + index : parameter.value();
            parameters.put(name, arguments[index]);
        });

        return parameters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(Collectors.joining(", "));
    }
}
