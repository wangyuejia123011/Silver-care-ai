package com.elderly.config;

import com.elderly.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
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

    /**
     * 数据库字段缺失类异常（线上库未跑升级 SQL 时最常见）。
     * 以前一律走兜底 handler，前端只能看到「系统繁忙」，无法定位。
     * 这里把 Unknown column / Table doesn't exist 翻译成可执行的提示。
     */
    @ExceptionHandler({BadSqlGrammarException.class, DataIntegrityViolationException.class})
    public R<Void> handleSql(Exception e) {
        log.error("数据库异常: {}", e.getMessage(), e);
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("Unknown column")) {
            String col = msg.contains("'") ? msg.substring(msg.indexOf('\'') + 1) : "";
            if (col.contains("'")) col = col.substring(0, col.indexOf('\''));
            return R.fail(500, "数据库缺少字段 " + col + "，请先执行对应的升级 SQL（sql 目录下的 upgrade-*.sql）后再试");
        }
        if (msg.contains("doesn't exist") || msg.contains("Table")) {
            return R.fail(500, "数据库表不存在，请先执行 sql 目录下的建表脚本");
        }
        if (msg.contains("Duplicate entry")) {
            return R.fail(500, "数据重复，请勿重复提交");
        }
        return R.fail(500, "数据库写入失败：" + (msg.length() > 120 ? msg.substring(0, 120) : msg));
    }

    /** 兜底：未预期的异常 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return R.fail(500, "系统繁忙，请稍后重试");
    }
}
