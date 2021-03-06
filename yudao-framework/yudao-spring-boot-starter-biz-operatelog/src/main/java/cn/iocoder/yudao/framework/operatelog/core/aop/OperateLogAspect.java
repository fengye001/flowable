package cn.iocoder.yudao.framework.operatelog.core.aop;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.operatelog.core.annotations.OperateLog;
import cn.iocoder.yudao.framework.operatelog.core.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.operatelog.core.enums.OperateTypeEnum;
import cn.iocoder.yudao.framework.operatelog.core.service.OperateLogFrameworkService;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import com.google.common.collect.Maps;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.SUCCESS;

/**
 * ???????????? @OperateLog ??????????????????????????????????????????????????????
 * ????????????????????????????????????????????????
 * 1. ?????? @ApiOperation + ??? @GetMapping
 * 2. ?????? @OperateLog ??????
 *
 * ????????????????????? @OperateLog ??????????????? enable ??????????????? false ????????????????????????
 *
 * @author ????????????
 */
@Aspect
@Slf4j
public class OperateLogAspect {

    /**
     * ????????????????????????????????????
     *
     * @see OperateLogCreateReqDTO#getContent()
     */
    private static final ThreadLocal<String> CONTENT = new ThreadLocal<>();
    /**
     * ????????????????????????????????????
     *
     * @see OperateLogCreateReqDTO#getExts()
     */
    private static final ThreadLocal<Map<String, Object>> EXTS = new ThreadLocal<>();

    @Resource
    private OperateLogFrameworkService operateLogFrameworkService;

    @Around("@annotation(apiOperation)")
    public Object around(ProceedingJoinPoint joinPoint, ApiOperation apiOperation) throws Throwable {
        // ?????????????????? @ApiOperation ??????
        OperateLog operateLog = getMethodAnnotation(joinPoint, OperateLog.class);
        return around0(joinPoint, operateLog, apiOperation);
    }

    @Around("!@annotation(io.swagger.annotations.ApiOperation) && @annotation(operateLog)") // ???????????????????????? @OperateLog ???????????????
    public Object around(ProceedingJoinPoint joinPoint, OperateLog operateLog) throws Throwable {
        return around0(joinPoint, operateLog, null);
    }

    private Object around0(ProceedingJoinPoint joinPoint, OperateLog operateLog, ApiOperation apiOperation) throws Throwable {
        // ??????????????????????????????????????????????????????????????????????????????????????????????????????
        Integer userType = WebFrameworkUtils.getLoginUserType();
        if (!Objects.equals(userType, UserTypeEnum.ADMIN.getValue())) {
            return joinPoint.proceed();
        }

        // ??????????????????
        Date startTime = new Date();
        try {
            // ??????????????????
            Object result = joinPoint.proceed();
            // ????????????????????????????????????
            this.log(joinPoint, operateLog, apiOperation, startTime, result, null);
            return result;
        } catch (Throwable exception) {
            this.log(joinPoint, operateLog, apiOperation, startTime, null, exception);
            throw exception;
        } finally {
            clearThreadLocal();
        }
    }

    public static void setContent(String content) {
        CONTENT.set(content);
    }

    public static void addExt(String key, Object value) {
        if (EXTS.get() == null) {
            EXTS.set(new HashMap<>());
        }
        EXTS.get().put(key, value);
    }

    private static void clearThreadLocal() {
        CONTENT.remove();
        EXTS.remove();
    }

    private void log(ProceedingJoinPoint joinPoint, OperateLog operateLog, ApiOperation apiOperation,
                     Date startTime, Object result, Throwable exception) {
        try {
            // ????????????????????????
            if (!isLogEnable(joinPoint, operateLog)) {
                return;
            }
            // ????????????????????????
            this.log0(joinPoint, operateLog, apiOperation, startTime, result, exception);
        } catch (Throwable ex) {
            log.error("[log][?????????????????????????????????????????????????????? joinPoint({}) operateLog({}) apiOperation({}) result({}) exception({}) ]",
                    joinPoint, operateLog, apiOperation, result, exception, ex);
        }
    }

    private void log0(ProceedingJoinPoint joinPoint, OperateLog operateLog, ApiOperation apiOperation,
                      Date startTime, Object result, Throwable exception) {
        OperateLogCreateReqDTO operateLogDTO = new OperateLogCreateReqDTO();
        // ??????????????????
        operateLogDTO.setTraceId(TracerUtils.getTraceId());
        operateLogDTO.setStartTime(startTime);
        // ??????????????????
        fillUserFields(operateLogDTO);
        // ??????????????????
        fillModuleFields(operateLogDTO, joinPoint, operateLog, apiOperation);
        // ??????????????????
        fillRequestFields(operateLogDTO);
        // ??????????????????
        fillMethodFields(operateLogDTO, joinPoint, operateLog, startTime, result, exception);

        // ??????????????????
        operateLogFrameworkService.createOperateLogAsync(operateLogDTO);
    }

    private static void fillUserFields(OperateLogCreateReqDTO operateLogDTO) {
        operateLogDTO.setUserId(WebFrameworkUtils.getLoginUserId());
        operateLogDTO.setUserType(WebFrameworkUtils.getLoginUserType());
    }

    private static void fillModuleFields(OperateLogCreateReqDTO operateLogDTO,
                                         ProceedingJoinPoint joinPoint, OperateLog operateLog, ApiOperation apiOperation) {
        // module ??????
        if (operateLog != null) {
            operateLogDTO.setModule(operateLog.module());
        }
        if (StrUtil.isEmpty(operateLogDTO.getModule())) {
            Api api = getClassAnnotation(joinPoint, Api.class);
            if (api != null) {
                // ???????????? @API ??? name ??????
                if (StrUtil.isNotEmpty(api.value())) {
                    operateLogDTO.setModule(api.value());
                }
                // ????????????????????? @API ??? tags ??????
                if (StrUtil.isEmpty(operateLogDTO.getModule()) && ArrayUtil.isNotEmpty(api.tags())) {
                    operateLogDTO.setModule(api.tags()[0]);
                }
            }
        }
        // name ??????
        if (operateLog != null) {
            operateLogDTO.setName(operateLog.name());
        }
        if (StrUtil.isEmpty(operateLogDTO.getName()) && apiOperation != null) {
            operateLogDTO.setName(apiOperation.value());
        }
        // type ??????
        if (operateLog != null && ArrayUtil.isNotEmpty(operateLog.type())) {
            operateLogDTO.setType(operateLog.type()[0].getType());
        }
        if (operateLogDTO.getType() == null) {
            RequestMethod requestMethod = obtainFirstMatchRequestMethod(obtainRequestMethod(joinPoint));
            OperateTypeEnum operateLogType = convertOperateLogType(requestMethod);
            operateLogDTO.setType(operateLogType != null ? operateLogType.getType() : null);
        }
        // content ??? exts ??????
        operateLogDTO.setContent(CONTENT.get());
        operateLogDTO.setExts(EXTS.get());
    }

    private static void fillRequestFields(OperateLogCreateReqDTO operateLogDTO) {
        // ?????? Request ??????
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) {
            return;
        }
        // ??????????????????
        operateLogDTO.setRequestMethod(request.getMethod());
        operateLogDTO.setRequestUrl(request.getRequestURI());
        operateLogDTO.setUserIp(ServletUtil.getClientIP(request));
        operateLogDTO.setUserAgent(ServletUtils.getUserAgent(request));
    }

    private static void fillMethodFields(OperateLogCreateReqDTO operateLogDTO,
                                         ProceedingJoinPoint joinPoint, OperateLog operateLog,
                                         Date startTime, Object result, Throwable exception) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        operateLogDTO.setJavaMethod(methodSignature.toString());
        if (operateLog == null || operateLog.logArgs()) {
            operateLogDTO.setJavaMethodArgs(obtainMethodArgs(joinPoint));
        }
        if (operateLog == null || operateLog.logResultData()) {
            operateLogDTO.setResultData(obtainResultData(result));
        }
        operateLogDTO.setDuration((int) (System.currentTimeMillis() - startTime.getTime()));
        // ?????????????????? resultCode ??? resultMsg ??????
        if (result != null) {
            if (result instanceof CommonResult) {
                CommonResult<?> commonResult = (CommonResult<?>) result;
                operateLogDTO.setResultCode(commonResult.getCode());
                operateLogDTO.setResultMsg(commonResult.getMsg());
            } else {
                operateLogDTO.setResultCode(SUCCESS.getCode());
            }
        }
        // ?????????????????? resultCode ??? resultMsg ??????
        if (exception != null) {
            operateLogDTO.setResultCode(INTERNAL_SERVER_ERROR.getCode());
            operateLogDTO.setResultMsg(ExceptionUtil.getRootCauseMessage(exception));
        }
    }

    private static boolean isLogEnable(ProceedingJoinPoint joinPoint, OperateLog operateLog) {
        // ??? @OperateLog ??????????????????
        if (operateLog != null) {
            return operateLog.enable();
        }
        // ?????? @ApiOperation ?????????????????????????????? POST???PUT???DELETE ?????????
        return obtainFirstLogRequestMethod(obtainRequestMethod(joinPoint)) != null;
    }

    private static RequestMethod obtainFirstLogRequestMethod(RequestMethod[] requestMethods) {
        if (ArrayUtil.isEmpty(requestMethods)) {
            return null;
        }
        return Arrays.stream(requestMethods).filter(requestMethod ->
                           requestMethod == RequestMethod.POST
                        || requestMethod == RequestMethod.PUT
                        || requestMethod == RequestMethod.DELETE)
                .findFirst().orElse(null);
    }

    private static RequestMethod obtainFirstMatchRequestMethod(RequestMethod[] requestMethods) {
        if (ArrayUtil.isEmpty(requestMethods)) {
            return null;
        }
        // ???????????????????????? POST???PUT???DELETE
        RequestMethod result = obtainFirstLogRequestMethod(requestMethods);
        if (result != null) {
            return result;
        }
        // ???????????????????????? GET
        result = Arrays.stream(requestMethods).filter(requestMethod -> requestMethod == RequestMethod.GET)
                .findFirst().orElse(null);
        if (result != null) {
            return result;
        }
        // ????????????????????????
        return requestMethods[0];
    }

    private static OperateTypeEnum convertOperateLogType(RequestMethod requestMethod) {
        if (requestMethod == null) {
            return null;
        }
        switch (requestMethod) {
            case GET:
                return OperateTypeEnum.GET;
            case POST:
                return OperateTypeEnum.CREATE;
            case PUT:
                return OperateTypeEnum.UPDATE;
            case DELETE:
                return OperateTypeEnum.DELETE;
            default:
                return OperateTypeEnum.OTHER;
        }
    }

    private static RequestMethod[] obtainRequestMethod(ProceedingJoinPoint joinPoint) {
        RequestMapping requestMapping = AnnotationUtils.getAnnotation( // ?????? Spring ??????????????????????????? @RequestMapping ????????????
                ((MethodSignature) joinPoint.getSignature()).getMethod(), RequestMapping.class);
        return requestMapping != null ? requestMapping.method() : new RequestMethod[]{};
    }

    @SuppressWarnings("SameParameterValue")
    private static <T extends Annotation> T getMethodAnnotation(ProceedingJoinPoint joinPoint, Class<T> annotationClass) {
        return ((MethodSignature) joinPoint.getSignature()).getMethod().getAnnotation(annotationClass);
    }

    @SuppressWarnings("SameParameterValue")
    private static <T extends Annotation> T getClassAnnotation(ProceedingJoinPoint joinPoint, Class<T> annotationClass) {
        return ((MethodSignature) joinPoint.getSignature()).getMethod().getDeclaringClass().getAnnotation(annotationClass);
    }

    private static String obtainMethodArgs(ProceedingJoinPoint joinPoint) {
        // TODO ??????????????????????????????
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        String[] argNames = methodSignature.getParameterNames();
        Object[] argValues = joinPoint.getArgs();
        // ????????????
        Map<String, Object> args = Maps.newHashMapWithExpectedSize(argValues.length);
        for (int i = 0; i < argNames.length; i++) {
            String argName = argNames[i];
            Object argValue = argValues[i];
            // ???????????????????????? ignore ????????????????????? null ????????????
            args.put(argName, !isIgnoreArgs(argValue) ? argValue : "[ignore]");
        }
        return JsonUtils.toJsonString(args);
    }

    private static String obtainResultData(Object result) {
        // TODO ??????????????????????????????
        if (result instanceof CommonResult) {
            result = ((CommonResult<?>) result).getData();
        }
        return JsonUtils.toJsonString(result);
    }

    private static boolean isIgnoreArgs(Object object) {
        Class<?> clazz = object.getClass();
        // ?????????????????????
        if (clazz.isArray()) {
            return IntStream.range(0, Array.getLength(object))
                    .anyMatch(index -> isIgnoreArgs(Array.get(object, index)));
        }
        // ????????????????????????Collection???Map ?????????
        if (Collection.class.isAssignableFrom(clazz)) {
            return ((Collection<?>) object).stream()
                    .anyMatch((Predicate<Object>) OperateLogAspect::isIgnoreArgs);
        }
        if (Map.class.isAssignableFrom(clazz)) {
            return isIgnoreArgs(((Map<?, ?>) object).values());
        }
        // obj
        return object instanceof MultipartFile
                || object instanceof HttpServletRequest
                || object instanceof HttpServletResponse
                || object instanceof BindingResult;
    }

}
