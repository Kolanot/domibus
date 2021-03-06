package eu.domibus.ebms3.sender;

import com.google.common.base.Strings;
import eu.domibus.api.configuration.DomibusConfigurationService;
import eu.domibus.api.property.DomibusPropertyProvider;
import eu.domibus.logging.DomibusLogger;
import eu.domibus.logging.DomibusLoggerFactory;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.configuration.security.ProxyAuthorizationPolicy;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.jaxws.DispatchImpl;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.policy.PolicyConstants;
import org.apache.neethi.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.xml.namespace.QName;
import javax.xml.soap.SOAPMessage;
import javax.xml.ws.Dispatch;
import javax.xml.ws.soap.SOAPBinding;
import java.util.concurrent.Executor;

/**
 * @author Cosmin Baciu
 * @since 3.3
 */
@Service
public class DispatchClientDefaultProvider implements DispatchClientProvider {

    private static final DomibusLogger LOG = DomibusLoggerFactory.getLogger(DispatchClientDefaultProvider.class);

    public static final String PMODE_KEY_CONTEXT_PROPERTY = "PMODE_KEY_CONTEXT_PROPERTY";
    public static final String ASYMMETRIC_SIG_ALGO_PROPERTY = "ASYMMETRIC_SIG_ALGO_PROPERTY";
    public static final String MESSAGE_ID = "MESSAGE_ID";
    public static final QName SERVICE_NAME = new QName("http://domibus.eu", "msh-dispatch-service");
    public static final QName PORT_NAME = new QName("http://domibus.eu", "msh-dispatch");
    public static final String DOMIBUS_DISPATCHER_CONNECTIONTIMEOUT = "domibus.dispatcher.connectionTimeout";
    public static final String DOMIBUS_DISPATCHER_CONNECTIONTIMEOUT_DEFAULT = "120000";
    public static final String DOMIBUS_DISPATCHER_RECEIVETIMEOUT = "domibus.dispatcher.receiveTimeout";
    public static final String DOMIBUS_DISPATCHER_RECEIVETIMEOUT_DEFAULT = "120000";
    public static final String DOMIBUS_DISPATCHER_ALLOWCHUNKING = "domibus.dispatcher.allowChunking";
    public static final String DOMIBUS_DISPATCHER_ALLOWCHUNKING_DEFAULT = "true";
    public static final String DOMIBUS_DISPATCHER_CHUNKINGTHRESHOLD = "domibus.dispatcher.chunkingThreshold";
    public static final String DOMIBUS_DISPATCHER_CHUNKINGTHRESHOLD_DEFAULT = "104857600";


    @Autowired
    private TLSReader tlsReader;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor executor;

    @Autowired
    protected DomibusPropertyProvider domibusPropertyProvider;


    @Cacheable(value = "dispatchClient", key = "#domain + #endpoint + #pModeKey", condition = "#cacheable")
    @Override
    public Dispatch<SOAPMessage> getClient(String domain, String endpoint, String algorithm, Policy policy, final String pModeKey, boolean cacheable) {
        LOG.debug("Getting the dispatch client for endpoint [{}] on domain [{}]", endpoint, domain);

        final Dispatch<SOAPMessage> dispatch = createWSServiceDispatcher(endpoint);
        dispatch.getRequestContext().put(PolicyConstants.POLICY_OVERRIDE, policy);
        dispatch.getRequestContext().put(ASYMMETRIC_SIG_ALGO_PROPERTY, algorithm);
        dispatch.getRequestContext().put(PMODE_KEY_CONTEXT_PROPERTY, pModeKey);
        final Client client = ((DispatchImpl<SOAPMessage>) dispatch).getClient();
        final HTTPConduit httpConduit = (HTTPConduit) client.getConduit();
        final HTTPClientPolicy httpClientPolicy = httpConduit.getClient();
        httpConduit.setClient(httpClientPolicy);
        //ConnectionTimeOut - Specifies the amount of time, in milliseconds, that the consumer will attempt to establish a connection before it times out. 0 is infinite.
        int connectionTimeout = Integer.parseInt(domibusPropertyProvider.getDomainProperty(DOMIBUS_DISPATCHER_CONNECTIONTIMEOUT, DOMIBUS_DISPATCHER_CONNECTIONTIMEOUT_DEFAULT));
        httpClientPolicy.setConnectionTimeout(connectionTimeout);
        //ReceiveTimeOut - Specifies the amount of time, in milliseconds, that the consumer will wait for a response before it times out. 0 is infinite.
        int receiveTimeout = Integer.parseInt(domibusPropertyProvider.getDomainProperty(DOMIBUS_DISPATCHER_RECEIVETIMEOUT, DOMIBUS_DISPATCHER_RECEIVETIMEOUT_DEFAULT));
        httpClientPolicy.setReceiveTimeout(receiveTimeout);
        httpClientPolicy.setAllowChunking(Boolean.valueOf(domibusPropertyProvider.getDomainProperty(DOMIBUS_DISPATCHER_ALLOWCHUNKING, DOMIBUS_DISPATCHER_ALLOWCHUNKING_DEFAULT)));
        httpClientPolicy.setChunkingThreshold(Integer.parseInt(domibusPropertyProvider.getDomainProperty(DOMIBUS_DISPATCHER_CHUNKINGTHRESHOLD, DOMIBUS_DISPATCHER_CHUNKINGTHRESHOLD_DEFAULT)));

        final TLSClientParameters params = tlsReader.getTlsClientParameters();
        if (params != null && endpoint.startsWith("https://")) {
            httpConduit.setTlsClientParameters(params);
        }
        final SOAPMessage result;

        String useProxy = domibusPropertyProvider.getProperty(DomibusConfigurationService.DOMIBUS_PROXY_ENABLED, "false");
        Boolean useProxyBool = Boolean.parseBoolean(useProxy);
        if (useProxyBool) {
            LOG.info("Usage of Proxy required");
            configureProxy(httpClientPolicy, httpConduit);
        } else {
            LOG.info("No proxy configured");
        }
        return dispatch;
    }

    protected Dispatch<SOAPMessage> createWSServiceDispatcher(String endpoint) {
        final javax.xml.ws.Service service = javax.xml.ws.Service.create(SERVICE_NAME);
        service.setExecutor(executor);
        service.addPort(PORT_NAME, SOAPBinding.SOAP12HTTP_BINDING, endpoint);
        final Dispatch<SOAPMessage> dispatch = service.createDispatch(PORT_NAME, SOAPMessage.class, javax.xml.ws.Service.Mode.MESSAGE);
        return dispatch;
    }

    protected void configureProxy(final HTTPClientPolicy httpClientPolicy, HTTPConduit httpConduit) {
        String httpProxyHost = domibusPropertyProvider.getProperty(DomibusConfigurationService.DOMIBUS_PROXY_HTTP_HOST);
        String httpProxyPort = domibusPropertyProvider.getProperty(DomibusConfigurationService.DOMIBUS_PROXY_HTTP_PORT);
        String httpProxyUser = domibusPropertyProvider.getProperty(DomibusConfigurationService.DOMIBUS_PROXY_USER);
        String httpProxyPassword = domibusPropertyProvider.getProperty(DomibusConfigurationService.DOMIBUS_PROXY_PASSWORD);
        String httpNonProxyHosts = domibusPropertyProvider.getProperty(DomibusConfigurationService.DOMIBUS_PROXY_NON_PROXY_HOSTS);
        if (!Strings.isNullOrEmpty(httpProxyHost) && !Strings.isNullOrEmpty(httpProxyPort)) {
            httpClientPolicy.setProxyServer(httpProxyHost);
            httpClientPolicy.setProxyServerPort(Integer.valueOf(httpProxyPort));
            httpClientPolicy.setProxyServerType(org.apache.cxf.transports.http.configuration.ProxyServerType.HTTP);
        }
        if (!Strings.isNullOrEmpty(httpProxyHost)) {
            httpClientPolicy.setNonProxyHosts(httpNonProxyHosts);
        }
        if (!Strings.isNullOrEmpty(httpProxyUser) && !Strings.isNullOrEmpty(httpProxyPassword)) {
            ProxyAuthorizationPolicy policy = new ProxyAuthorizationPolicy();
            policy.setUserName(httpProxyUser);
            policy.setPassword(httpProxyPassword);
            httpConduit.setProxyAuthorization(policy);
        }
    }


}
