package eu.domibus.web.rest.error;

import eu.domibus.api.multitenancy.DomainException;
import eu.domibus.ext.rest.ErrorRO;
import eu.domibus.logging.DomibusLogger;
import eu.domibus.logging.DomibusLoggerFactory;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import javax.persistence.RollbackException;

@ControllerAdvice
@RequestMapping(produces = "application/vnd.error+json")
public class RestControllerAdvice extends ResponseEntityExceptionHandler {

    private static final DomibusLogger LOG = DomibusLoggerFactory.getLogger(RestControllerAdvice.class);

    @ExceptionHandler({DomainException.class})
    public ResponseEntity<ErrorRO> handleDomainException(DomainException ex) {
        return handleWrappedException(ex);
    }

    @ExceptionHandler({RollbackException.class})
    public ResponseEntity<ErrorRO> handleRollbackException(RollbackException ex) {
        return handleWrappedException(ex);
    }

    @ExceptionHandler({Exception.class})
    public ResponseEntity<ErrorRO> handleException(Exception ex) {
        LOG.error(ex.getMessage(), ex);
        return new ResponseEntity(new ErrorRO(ex.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorRO> handleWrappedException(Exception ex) {
        LOG.error(ex.getMessage(), ex);

        Throwable rootCause = ExceptionUtils.getRootCause(ex) == null ? ex : ExceptionUtils.getRootCause(ex);
        return new ResponseEntity(new ErrorRO(rootCause.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
