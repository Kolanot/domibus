package eu.domibus.pmode;

import com.google.common.io.Files;
import eu.domibus.api.util.xml.UnmarshallerResult;
import eu.domibus.api.util.xml.XMLUtil;
import eu.domibus.common.model.configuration.Mpc;
import eu.domibus.common.model.configuration.Mpcs;
import eu.domibus.xml.XMLUtilImpl;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by Cosmin Baciu on 16-Sep-16.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
public class SamplePModeTestIT {

    @Configuration
    static class ContextConfiguration {

        @Bean
        public XMLUtil xmlUtil() {
            XMLUtilImpl xmlUtil = new XMLUtilImpl();
            return xmlUtil;
        }

        @Bean
        public JAXBContext createJaxbContent() throws JAXBException {
            return JAXBContext.newInstance("eu.domibus.common.model.configuration");
        }
    }

    @Autowired
    XMLUtil xmlUtil;

    @Autowired
    JAXBContext jaxbContext;

    @Test
    public void testRetentionValuesForBluePmode() throws Exception {
        testRetentionUndownloadedIsBiggerThanZero("src/main/conf/pmodes/domibus-gw-sample-pmode-blue.xml");
    }

    @Test
    public void testRetentionValuesForRedPmode() throws Exception {
        testRetentionUndownloadedIsBiggerThanZero("src/main/conf/pmodes/domibus-gw-sample-pmode-red.xml");
    }

    protected void testRetentionUndownloadedIsBiggerThanZero(String location) throws Exception {
        eu.domibus.common.model.configuration.Configuration  bluePmode = readPMode(location);
        assertNotNull(bluePmode);
        Mpcs mpcsXml = bluePmode.getMpcsXml();
        assertNotNull(mpcsXml);
        List<Mpc> mpcList = mpcsXml.getMpc();
        assertNotNull(mpcList);
        for (Mpc mpc : mpcList) {
            assertTrue(mpc.getRetentionUndownloaded() > 0);
            assertTrue(mpc.getRetentionDownloaded() == 0);
        }
    }

    protected eu.domibus.common.model.configuration.Configuration  readPMode(String location) throws Exception {
        File pmodeFile = new File(location);
        InputStream is = getClass().getClassLoader().getResourceAsStream("samplePModes/" + location);
        String pmodeContent = FileUtils.readFileToString(pmodeFile);
        pmodeContent = StringUtils.replaceEach(pmodeContent, new String[]{"<red_hostname>", "<blue_hostname>"}, new String[]{"red_hostname", "blue_hostname"});

        UnmarshallerResult unmarshal = xmlUtil.unmarshal(false, jaxbContext, IOUtils.toInputStream(pmodeContent), null);
        return unmarshal.getResult();
    }

    public static final String SCHEMAS_DIR = "schemas/";
    public static final String DOMIBUS_PMODE_XSD = "domibus-pmode.xsd";

    @Test
    public void testMarshalling() throws Exception {

        InputStream xsdStream = getClass().getClassLoader().getResourceAsStream(SCHEMAS_DIR + DOMIBUS_PMODE_XSD);
        InputStream xsdStream2 = getClass().getClassLoader().getResourceAsStream(SCHEMAS_DIR + DOMIBUS_PMODE_XSD);
        InputStream xsdStream3 = getClass().getClassLoader().getResourceAsStream(SCHEMAS_DIR + DOMIBUS_PMODE_XSD);
        InputStream xsdStream4 = getClass().getClassLoader().getResourceAsStream(SCHEMAS_DIR + DOMIBUS_PMODE_XSD);

        InputStream is = getClass().getClassLoader().getResourceAsStream("samplePModes/domibus-configuration-valid.xml");
        byte[] bytes = IOUtils.toByteArray(is);

        ByteArrayInputStream xmlStream = new ByteArrayInputStream(bytes);

        UnmarshallerResult unmarshallerResult = xmlUtil.unmarshal(false, jaxbContext, xmlStream, xsdStream);
        eu.domibus.common.model.configuration.Configuration configuration = unmarshallerResult.getResult();

        byte[] bytes2 = xmlUtil.marshal(jaxbContext, configuration, xsdStream2);
        xmlStream = new ByteArrayInputStream(bytes2);
        unmarshallerResult = xmlUtil.unmarshal(false, jaxbContext, xmlStream, xsdStream3);
        eu.domibus.common.model.configuration.Configuration configuration2 = unmarshallerResult.getResult();

        byte[] bytes3 = xmlUtil.marshal(jaxbContext, configuration2, xsdStream4);

        assertTrue(Arrays.equals(bytes2,bytes3));
        assertNotNull(configuration2.getBusinessProcesses());
    }

    private InputStream getXsdStream() {
        return getClass().getClassLoader().getResourceAsStream(SCHEMAS_DIR + DOMIBUS_PMODE_XSD);
    }
}
