package eu.domibus.common.services.impl;

import com.codahale.metrics.Timer;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import eu.domibus.api.exceptions.DomibusCoreErrorCode;
import eu.domibus.api.message.UserMessageLogService;
import eu.domibus.api.metrics.Metrics;
import eu.domibus.api.pmode.PModeException;
import eu.domibus.api.reliability.ReliabilityException;
import eu.domibus.api.security.ChainCertificateInvalidException;
import eu.domibus.common.MSHRole;
import eu.domibus.common.MessageStatus;
import eu.domibus.common.dao.MessagingDao;
import eu.domibus.common.dao.RawEnvelopeLogDao;
import eu.domibus.common.exception.EbMS3Exception;
import eu.domibus.common.model.configuration.Identifier;
import eu.domibus.common.model.configuration.LegConfiguration;
import eu.domibus.common.model.configuration.Party;
import eu.domibus.common.model.configuration.Process;
import eu.domibus.common.model.logging.RawEnvelopeDto;
import eu.domibus.common.model.logging.RawEnvelopeLog;
import eu.domibus.common.services.MessageExchangeService;
import eu.domibus.common.validators.ProcessValidator;
import eu.domibus.core.pull.MessagingLockException;
import eu.domibus.core.pull.MessagingLockService;
import eu.domibus.ebms3.common.context.MessageExchangeConfiguration;
import eu.domibus.ebms3.common.dao.PModeProvider;
import eu.domibus.ebms3.common.model.UserMessage;
import eu.domibus.ebms3.receiver.MSHWebservice;
import eu.domibus.ebms3.sender.SaveRawPulledMessageInterceptor;
import eu.domibus.logging.DomibusLogger;
import eu.domibus.logging.DomibusLoggerFactory;
import eu.domibus.pki.CertificateService;
import eu.domibus.pki.DomibusCertificateException;
import eu.domibus.pki.PolicyService;
import eu.domibus.wss4j.common.crypto.CryptoService;
import org.apache.neethi.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;
import static eu.domibus.api.metrics.Metrics.METRIC_REGISTRY;
import static eu.domibus.common.MessageStatus.READY_TO_PULL;
import static eu.domibus.common.MessageStatus.SEND_ENQUEUED;
import static eu.domibus.common.services.impl.PullContext.*;

/**
 * @author Thomas Dussart
 * @since 3.3
 * {@inheritDoc}
 */
@Service
public class MessageExchangeServiceImpl implements MessageExchangeService {

    private static final DomibusLogger LOG = DomibusLoggerFactory.getLogger(MessageExchangeServiceImpl.class);

    private static final String DOMIBUS_RECEIVER_CERTIFICATE_VALIDATION_ONSENDING = "domibus.receiver.certificate.validation.onsending";

    private static final String DOMIBUS_SENDER_CERTIFICATE_VALIDATION_ONSENDING = "domibus.sender.certificate.validation.onsending";

    private static final String DOMIBUS_PULL_REQUEST_SEND_PER_JOB_CYCLE = "domibus.pull.request.send.per.job.cycle";

    @Autowired
    private MessagingDao messagingDao;

    @Autowired
    @Qualifier("pullMessageQueue")
    private Queue pullMessageQueue;

    @Autowired
    private JmsTemplate jmsPullTemplate;

    @Autowired
    private UserMessageLogService userMessageLogService;

    @Autowired
    private RawEnvelopeLogDao rawEnvelopeLogDao;

    @Autowired
    private ProcessValidator processValidator;

    @Autowired
    private PModeProvider pModeProvider;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private CertificateService certificateService;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    @Qualifier("domibusProperties")
    private java.util.Properties domibusProperties;

    @Autowired
    private MessagingLockService messagingLockService;


    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public MessageStatus getMessageStatus(final MessageExchangeConfiguration messageExchangeConfiguration) {
        MessageStatus messageStatus = SEND_ENQUEUED;
        List<Process> processes = pModeProvider.findPullProcessesByMessageContext(messageExchangeConfiguration);
        if (!processes.isEmpty()) {
            processValidator.validatePullProcess(Lists.newArrayList(processes));
            messageStatus = READY_TO_PULL;
        } else {
            LOG.debug("No pull process found for message configuration");
        }
        return messageStatus;
    }

    /**
     * {@inheritDoc}@
     */
    @Override
    @Transactional(readOnly = true)
    public MessageStatus retrieveMessageRestoreStatus(final String messageId) {
        final UserMessage userMessage = messagingDao.findUserMessageByMessageId(messageId);
        try {
            MessageExchangeConfiguration userMessageExchangeConfiguration = pModeProvider.findUserMessageExchangeContext(userMessage, MSHRole.SENDING);
            return getMessageStatus(userMessageExchangeConfiguration);
        } catch (EbMS3Exception e) {
            throw new PModeException(DomibusCoreErrorCode.DOM_001, "Could not get the PMode key for message [" + messageId + "]", e);
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void initiatePullRequest() {
        //TODO taxud change this behavior as the pull request do not start if not incoming message has been pushed
        final boolean configurationLoaded = pModeProvider.isConfigurationLoaded();
        if (!configurationLoaded) {
            LOG.debug("A configuration problem occurred while initiating the pull request. Probably no configuration is loaded.");
            return;
        }
        Party initiator = pModeProvider.getGatewayParty();
        List<Process> pullProcesses = pModeProvider.findPullProcessesByInitiator(initiator);
        LOG.debug("Initiating pull requests:");
        for (Process pullProcess : pullProcesses) {
            try {
                processValidator.validatePullProcess(Lists.newArrayList(pullProcess));
                for (LegConfiguration legConfiguration : pullProcess.getLegs()) {
                    for (Party initiatorParty : pullProcess.getResponderParties()) {
                        String mpcQualifiedName = legConfiguration.getDefaultMpc().getQualifiedName();
                        //@thom remove the pullcontext from here.
                        PullContext pullContext = new PullContext(pullProcess,
                                initiatorParty,
                                mpcQualifiedName);
                        MessageExchangeConfiguration messageExchangeConfiguration = new MessageExchangeConfiguration(pullContext.getAgreement(),
                                initiatorParty.getName(),
                                initiator.getName(),
                                legConfiguration.getService().getName(),
                                legConfiguration.getAction().getName(),
                                legConfiguration.getName());
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(messageExchangeConfiguration.toString());
                        }
                        final Map<String, String> map = Maps.newHashMap();
                        map.put(MPC, mpcQualifiedName);
                        map.put(PMODE_KEY, messageExchangeConfiguration.getReversePmodeKey());
                        map.put(PullContext.NOTIFY_BUSINNES_ON_ERROR, String.valueOf(legConfiguration.getErrorHandling().isBusinessErrorNotifyConsumer()));
                        MessagePostProcessor postProcessor = new MessagePostProcessor() {
                            public Message postProcessMessage(Message message) throws JMSException {
                                message.setStringProperty(MPC, map.get(MPC));
                                message.setStringProperty(PMODE_KEY, map.get(PMODE_KEY));
                                message.setStringProperty(NOTIFY_BUSINNES_ON_ERROR, map.get(NOTIFY_BUSINNES_ON_ERROR));
                                return message;
                            }
                        };
                        //TODO extract this outside of the loop for clarity.
                        Integer numberOfPullRequestPerMpc = Integer.valueOf(domibusProperties.getProperty(DOMIBUS_PULL_REQUEST_SEND_PER_JOB_CYCLE, "1"));
                        LOG.debug("Sending:[{}] pull request for mpc:[{}]",numberOfPullRequestPerMpc,mpcQualifiedName);
                        for (int i = 0; i < numberOfPullRequestPerMpc; i++) {
                            jmsPullTemplate.convertAndSend(pullMessageQueue, map, postProcessor);
                        }

                    }
                }
            } catch (PModeException e) {
                LOG.warn("Invalid pull process configuration found during pull try " + e.getMessage());
            }
        }

    }

    //TODO change this mechanism.
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public String retrieveReadyToPullUserMessageId(final String mpc, final Party initiator) {
        Timer.Context retrieveReadyToPullUserMessageId = METRIC_REGISTRY.timer(name(MessageExchangeService.class, "pull.retrieveReadyToPullUserMessageId")).time();
        Set<Identifier> identifiers = initiator.getIdentifiers();
        if (identifiers.size() == 0) {
            LOG.warn("No identifier found for party:[{}]", initiator.getName());
            return null;
        }
        String partyId = identifiers.iterator().next().getPartyId();
        String pullMessageToProcess = messagingLockService.getPullMessageToProcess(partyId, mpc);
        retrieveReadyToPullUserMessageId.close();
        return pullMessageToProcess;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PullContext extractProcessOnMpc(final String mpcQualifiedName) {
        Timer.Context extractProcessOnMpc = METRIC_REGISTRY.timer(name(MessageExchangeService.class, "pull.extractProcessOnMpc")).time();
        try {
            final Party gatewayParty = pModeProvider.getGatewayParty();
            List<Process> processes = pModeProvider.findPullProcessByMpc(mpcQualifiedName);
            if(LOG.isDebugEnabled()){
                for (Process process : processes) {
                    LOG.debug("Process:[{}] correspond to mpc:[{}]",process.getName(),mpcQualifiedName);
                }
            }
            processValidator.validatePullProcess(processes);
            extractProcessOnMpc.stop();
            return new PullContext(processes.get(0), gatewayParty, mpcQualifiedName);
        } catch (IllegalArgumentException e) {
            throw new PModeException(DomibusCoreErrorCode.DOM_003, "No pmode configuration found");
        }
    }


    @Override
    @Transactional(noRollbackFor = ReliabilityException.class)
    public void removeRawMessageIssuedByPullRequest(final String messageId) {
        rawEnvelopeLogDao.deleteUserMessageRawEnvelope(messageId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void removeRawMessageIssuedByPullRequestInNewTransaction(String messageId) {
        removeRawMessageIssuedByPullRequest(messageId);
    }

    @Override
    @Transactional
    public void savePulledMessageRawXml(final String rawXml, final String messageId) {
        UserMessage userMessage = messagingDao.findUserMessageByMessageId(messageId);
        final Timer.Context savePulledMessageRawXml = Metrics.METRIC_REGISTRY.timer(name(SaveRawPulledMessageInterceptor.class, "savePulledMessageRawXml")).time();
        RawEnvelopeLog rawEnvelopeLog = new RawEnvelopeLog();
        rawEnvelopeLog.setRawXML(rawXml);
        rawEnvelopeLog.setUserMessage(userMessage);
        rawEnvelopeLogDao.create(rawEnvelopeLog);
        savePulledMessageRawXml.stop();
    }

    @Override
    @Transactional(noRollbackFor = ReliabilityException.class)
    public RawEnvelopeDto findPulledMessageRawXmlByMessageId(final String messageId) {
        final RawEnvelopeDto rawXmlByMessageId = rawEnvelopeLogDao.findRawXmlByMessageId(messageId);
        if (rawXmlByMessageId == null) {
            throw new ReliabilityException(DomibusCoreErrorCode.DOM_004, "There should always have a raw message for message " + messageId);
        }
        return rawXmlByMessageId;
    }


    @Override
    @Transactional(noRollbackFor = ChainCertificateInvalidException.class)
    public void verifyReceiverCertificate(final LegConfiguration legConfiguration, String receiverName) {
        Policy policy = policyService.parsePolicy("policies/" + legConfiguration.getSecurity().getPolicy());
        if (policyService.isNoSecurityPolicy(policy)) {
            return;
        }
        if (Boolean.parseBoolean(domibusProperties.getProperty(DOMIBUS_RECEIVER_CERTIFICATE_VALIDATION_ONSENDING, "true"))) {
            String chainExceptionMessage = "Cannot send message: receiver certificate is not valid or it has been revoked [" + receiverName + "]";
            try {
                boolean certificateChainValid = certificateService.isCertificateChainValid(receiverName);
                if (!certificateChainValid) {
                    LOG.error("Receiver Cerfitication chaing not valid [{}]",chainExceptionMessage);
                    throw new ChainCertificateInvalidException(DomibusCoreErrorCode.DOM_001, chainExceptionMessage);
                }
                LOG.debug("Receiver certificate exists and is valid [" + receiverName + "]");
            } catch (DomibusCertificateException e) {
                LOG.error("Receiver DomibusCertificateException [{}]",chainExceptionMessage);
                throw new ChainCertificateInvalidException(DomibusCoreErrorCode.DOM_001, chainExceptionMessage, e);
            }
        }
    }

    @Override
    @Transactional(noRollbackFor = ChainCertificateInvalidException.class)
    public void verifySenderCertificate(final LegConfiguration legConfiguration, String senderName) {
        Policy policy = policyService.parsePolicy("policies/" + legConfiguration.getSecurity().getPolicy());
        if (policyService.isNoSecurityPolicy(policy)) {
            return;
        }
        if (Boolean.parseBoolean(domibusProperties.getProperty(DOMIBUS_SENDER_CERTIFICATE_VALIDATION_ONSENDING, "true"))) {
            String chainExceptionMessage = "Cannot send message: sender certificate is not valid or it has been revoked [" + senderName + "]";
            try {
                X509Certificate certificate = (X509Certificate) cryptoService.getCertificateFromKeystore(senderName);
                if (certificate == null) {
                    throw new ChainCertificateInvalidException(DomibusCoreErrorCode.DOM_001, "Cannot send message: sender[" + senderName + "] certificate not found in Keystore");
                }
                if (!certificateService.isCertificateValid(certificate)) {
                    LOG.error("Sender certificate is null",chainExceptionMessage);
                    throw new ChainCertificateInvalidException(DomibusCoreErrorCode.DOM_001, chainExceptionMessage);
                }
                LOG.debug("Sender certificate exists and is valid [" + senderName + "]");
            } catch (DomibusCertificateException | KeyStoreException ex) {
                // Is this an error and we stop the sending or we just log a warning that we were not able to validate the cert?
                // my opinion is that since the option is enabled, we should validate no matter what => this is an error
                throw new ChainCertificateInvalidException(DomibusCoreErrorCode.DOM_001, chainExceptionMessage, ex);
            }
        }
    }
}

