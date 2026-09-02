package com.elderly.config;

import com.elderly.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器 —— 统一拦截Controller层抛出的异常，返回标准化R结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return R.fail(400, msg);
    }

    /** 文件上传超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大: {}", e.getMessage());
        return R.fail(400, "录音文件过大，请控制在30秒以内");
    }

    /** 非法参数 */
    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArg(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return R.fail(400, e.getMessage());
    }

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 兜底：未预期的异常 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return R.fail(500, "系统繁忙，请稍后重试");
    }
}
