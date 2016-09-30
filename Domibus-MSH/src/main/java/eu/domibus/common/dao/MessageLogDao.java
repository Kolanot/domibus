package eu.domibus.common.dao;

import eu.domibus.common.MSHRole;
import eu.domibus.common.MessageStatus;
import eu.domibus.common.model.logging.MessageLog;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.sql.Timestamp;
import java.util.*;

/**
 * @param <F>
 * @author Federico Martini
 * @since 3.2
 */
public abstract class MessageLogDao<F extends MessageLog> extends BasicDao {

    protected static final Log logger = LogFactory.getLog(MessageLog.class);

    public <F extends MessageLog> MessageLogDao(final Class<F> type) {

        super(type);
    }

    public void setMessageAsDeleted(String messageId) {
        setMessageStatus(messageId, MessageStatus.DELETED);
    }

    public void setMessageAsAcknowledged(String messageId) {
        setMessageStatus(messageId, MessageStatus.ACKNOWLEDGED);
    }

    public void setMessageAsAckWithWarnings(String messageId) {
        setMessageStatus(messageId, MessageStatus.ACKNOWLEDGED_WITH_WARNING);
    }

    public void setMessageAsWaitingForReceipt(String messageId) {
        setMessageStatus(messageId, MessageStatus.WAITING_FOR_RECEIPT);
    }

    public void setMessageAsSendFailure(String messageId) {
        setMessageStatus(messageId, MessageStatus.SEND_FAILURE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private void setMessageStatus(String messageId, MessageStatus messageStatus) {

        MessageLog messageLog = findByMessageId(messageId);
        messageLog.setMessageStatus(messageStatus);

        switch (messageStatus) {
            case DELETED:
                messageLog.setDeleted(new Date());
                break;
            default:
        }
        super.update(messageLog);
        logger.debug("Message Log status updated to [" + messageStatus + "]");
    }

    public MessageStatus getMessageStatus(String messageId) {
        try {
            return findByMessageId(messageId).getMessageStatus();
        } catch (NoResultException nrEx) {
            logger.debug("No result for message with id [" + messageId + "]", nrEx);
            return MessageStatus.NOT_FOUND;
        }
    }

    protected abstract MessageLog findByMessageId(String messageId);

    protected abstract MessageLog findByMessageId(String messageId, MSHRole mshRole);

    protected abstract Long countMessages(HashMap<String, Object> filters);

    protected abstract List<? extends MessageLog> findPaged(int from, int max, String column, boolean asc, HashMap<String, Object> filters);

    protected List<Predicate> getPredicates(HashMap<String, Object> filters, CriteriaBuilder cb, Root<? extends MessageLog> mle) {
        List<Predicate> predicates = new ArrayList<>();
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            if (filter.getValue() != null) {
                if (filter.getValue() instanceof String) {
                    if (!filter.getValue().toString().isEmpty()) {
                        switch (filter.getKey().toString()) {
                            case "receivedFrom":
                                predicates.add(cb.greaterThanOrEqualTo(mle.<Date>get("received"), Timestamp.valueOf(filter.getValue().toString())));
                                break;
                            case "receivedTo":
                                predicates.add(cb.lessThanOrEqualTo(mle.<Date>get("received"), Timestamp.valueOf(filter.getValue().toString())));
                                break;
                            default:
                                predicates.add(cb.like(mle.<String>get(filter.getKey()), (String) filter.getValue()));
                                break;
                        }
                    }
                } else {
                    predicates.add(cb.equal(mle.<String>get(filter.getKey()), filter.getValue()));
                }
            }
        }
        return predicates;
    }


}