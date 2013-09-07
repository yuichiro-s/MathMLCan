/**
 * Copyright 2013 MIRMU Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package cz.muni.fi.mir.mathmlcanonicalization;

import cz.muni.fi.mir.mathmlcanonicalization.modules.*;
import java.io.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.apache.commons.io.IOUtils;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.XMLOutputter;
import org.xml.sax.SAXException;

/**
 * An input class for MathML canonicalization.
 *
 * @author David Formanek
 */
public final class MathMLCanonicalizer {

    private List<StreamModule> streamModules = new LinkedList<StreamModule>();
    private List<DOMModule> domModules = new LinkedList<DOMModule>();
    private static final Logger LOGGER = Logger.getLogger(MathMLCanonicalizer.class.getName());

    // TODO: refactoring
    /**
     * Initializes canonicalizer with default settings
     *
     * @return itialized canonicalizer
     */
    public static MathMLCanonicalizer getDefaultCanonicalizer() {
        String property = Settings.getProperty("modules");
        String[] modules = property.split(" ");
        List<String> listOfModules = Arrays.asList(modules);

        MathMLCanonicalizer result = new MathMLCanonicalizer();
        for (String moduleName : listOfModules) {
            result.addModule(moduleName);
        }

        return result;
    }

    /**
     * Initializes canonicalizer with no modules
     */
    public MathMLCanonicalizer() {
    }

    /**
     * Initializes canonicalizer using configuration file
     *
     * @param xmlConfigurationStream XML configuration constrained by XML Schema
     *                               in res/configuration.xsd file
     * @throws ConfigException when configuration cannot be loaded 
     */
    public MathMLCanonicalizer(InputStream xmlConfigurationStream) throws ConfigException {
        if (xmlConfigurationStream == null) {
            throw new IllegalArgumentException("xmlConfigurationStream is null");
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(xmlConfigurationStream, baos);
            
            validateXMLConfiguration(new ByteArrayInputStream(baos.toByteArray()));
            loadXMLConfiguration(new ByteArrayInputStream(baos.toByteArray()));
        } catch (XMLStreamException ex) {
            LOGGER.log(Level.SEVERE, "cannot load configuration. ", ex);
            throw new ConfigException("cannot load configuration", ex);
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "cannot load configuration. ", ex);
            throw new ConfigException("cannot load configuration", ex);
        }
    }

    /**
     * Adds the module to the process of canonicalization
     *
     * StreamModules are called before DOM modules and then in order of
     * addition. Each module can be added more than once if needed.
     *
     * @param module the module to be used in canonicalization
     * @return the canonizer object to allow adding more modules at once
     */
    public MathMLCanonicalizer addModule(Module module) {
        if (module instanceof StreamModule) {
            if (module instanceof DOMModule) {
                LOGGER.log(Level.INFO, "Module is stream and DOM module at the"
                        + " same time, it will be used as a stream module.");
            }
            streamModules.add((StreamModule) module);
        } else if (module instanceof DOMModule) {
            domModules.add((DOMModule) module);
        } else {
            throw new UnsupportedOperationException("Module type not supported");
        }
        return this;
    }

    /**
     * Adds the module by its class name.
     * 
     * Useful for setting modules from config files. When the module can't be
     * found or instantiated the module is skipped and the warning is produced.
     * 
     * @param moduleName the name of the module class
     * @return the canonizer object to allow adding more modules at once
     */
    public MathMLCanonicalizer addModule(String moduleName) {
        try {
            String fullyQualified = this.getClass().getPackage().getName()
                    + ".modules." + moduleName;
            Class<?> moduleClass = Class.forName(fullyQualified);

            return addModule((Module) moduleClass.newInstance());
        } catch (ClassNotFoundException ex) {
            LOGGER.log(Level.WARNING, "cannot load module " + moduleName, ex);
        } catch (InstantiationException ex) {
            LOGGER.log(Level.WARNING, "cannot instantiate module " + moduleName, ex);
        } catch (IllegalAccessException ex) {
            LOGGER.log(Level.WARNING, "cannot access module " + moduleName, ex);
        }
        return this;
    }

    /**
     * Validate the configuration against XML Schema.
     * @throws ConfigException if not valid
     */
    private void validateXMLConfiguration(InputStream xmlConfigurationStream)
            throws IOException, ConfigException {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            Schema schema = sf.newSchema(MathMLCanonicalizer.class.getResource(
                    Settings.getProperty("configSchema")));

            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(xmlConfigurationStream));
        } catch (SAXException ex) {
            throw new ConfigException("configuration not valid\n" + ex.getMessage(), ex);
        }
    }
    
    /**
     * Loads configuration from XML file, overriding the properties.
     */
    private void loadXMLConfiguration(InputStream xmlConfigurationStream)
            throws ConfigException, XMLStreamException {
        final XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        final XMLStreamReader reader = inputFactory.createXMLStreamReader(xmlConfigurationStream);

        boolean config = false;
        Module module = null;
        while (reader.hasNext()) {
            final int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT: {
                    String name = reader.getLocalName();
                    if (name.equals("config")) {
                        config = true;
                        break;
                    }

                    if (config && name.equals("module")) {
                        if (reader.getAttributeCount() == 1) {
                            final String attributeName = reader.getAttributeLocalName(0);
                            final String attributeValue = reader.getAttributeValue(0);

                            if (attributeName.equals("name") && attributeValue != null) {
                                String fullyQualified = Settings.class.getPackage().getName()
                                        +".modules." + attributeValue;
                                try {
                                    Class<?> moduleClass = Class.forName(fullyQualified);
                                    module = (Module) moduleClass.newInstance();
                                } catch (InstantiationException ex) {
                                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                                    throw new ConfigException("cannot instantiate module "
                                            + attributeValue, ex);
                                } catch (IllegalAccessException ex) {
                                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                                    throw new ConfigException("cannot access module "
                                            + attributeValue, ex);
                                } catch (ClassNotFoundException ex) {
                                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                                    throw new ConfigException("cannot load module "
                                            + attributeValue, ex);
                                }
                            }
                        }
                    }

                    if (config && name.equals("property")) {
                        if (reader.getAttributeCount() == 1) {
                            final String attributeName = reader.getAttributeLocalName(0);
                            final String attributeValue = reader.getAttributeValue(0);

                            if (attributeName.equals("name") && attributeValue != null) {
                                if (module == null) {
                                    if (Settings.getProperty(attributeValue) != null) {
                                        Settings.setProperty(attributeValue, reader.getElementText());
                                    } else {
                                        throw new ConfigException("configuration not valid\n"
                                                + "Tried to override non-existing global property "
                                                + attributeValue);
                                    }
                                } else {
                                    if (module.getProperty(attributeValue) != null) {
                                        module.setProperty(attributeValue, reader.getElementText());
                                    } else {
                                        throw new ConfigException("configuration not valid\n"
                                                + "configuration tried to override non-existing property "
                                                + attributeValue);
                                    }
                                }
                            }
                        }
                    }

                    break;
                }
                case XMLStreamConstants.END_ELEMENT: {
                    if (config && reader.getLocalName().equals("module")) {
                        addModule(module);

                        module = null;
                    }

                    if (config && reader.getLocalName().equals("config")) {
                        config = false;
                    }
                }
            }
        }
    }

    /**
     * Canonicalize an input MathML stream.
     *
     * @param in input stream to be canonicalized
     * @param out canonical output stream of input
     * @throws JDOMException problem with DOM
     * @throws IOException problem with streams
     * @throws ModuleException some module cannot canonicalize the input 
     */
    public void canonicalize(InputStream in, OutputStream out)
            throws JDOMException, IOException, ModuleException {

        ByteArrayOutputStream outputStream = null;

        // calling stream modules
        for (StreamModule module : streamModules) {
            outputStream = module.execute(in);
            if (outputStream == null) {
                throw new IOException("Module " + module.toString() + "returned null.");
            }
            in = new ByteArrayInputStream(outputStream.toByteArray());
        }

        // do not create the JDOM representation if there are no modules
        if (domModules.isEmpty()) {
            if (streamModules.isEmpty()) {
                throw new IOException("There are no modules added.");
            }
            outputStream.writeTo(out);
            return;
        }

        // creating the JDOM representation from the stream
        SAXBuilder builder = Settings.setupSAXBuilder();
        Document document = builder.build(in);

        // calling JDOM modules
        for (DOMModule module : domModules) {
            module.execute(document);
        }

        // convertong the JDOM representation back to stream
        XMLOutputter serializer = new XMLOutputter();
        serializer.output(document, out);
    }
}
