package eu.domibus.wss4j.common.crypto;

import eu.domibus.api.multitenancy.Domain;
import eu.domibus.logging.DomibusLogger;
import eu.domibus.logging.DomibusLoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * @author Cosmin Baciu
 * @since 4.0
 */
@Configuration
public class DomainCertificateProviderConfiguration {

    private static final DomibusLogger LOG = DomibusLoggerFactory.getLogger(DomainCertificateProviderConfiguration.class);

    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public DomainCertificateProviderImpl domainCertificateProviderImpl(Domain domain) {
        LOG.debug("Instantiating the certificate provider for domain [{}]", domain);

        final DomainCertificateProviderImpl domainCertificateProvider = new DomainCertificateProviderImpl(domain);
        return domainCertificateProvider;
    }


}