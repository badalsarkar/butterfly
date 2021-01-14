package com.paypal.butterfly.utilities.operations.pom;

import com.paypal.butterfly.extensions.api.TOExecutionResult;
import com.paypal.butterfly.extensions.api.TransformationContext;
import com.paypal.butterfly.extensions.api.exception.TransformationOperationException;
import com.paypal.butterfly.extensions.api.operations.AddElement;
import com.paypal.butterfly.utilities.operations.pom.stax.EndElementEventCondition;
import com.paypal.butterfly.utilities.operations.pom.stax.StartElementEventCondition;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.io.File;
import java.io.IOException;

/**
 * Adds a new dependency to a POM file.
 * If the POM file already has the specified dependency, the operation will return an error.
 * That behavior can be changed though, see {@link AddElement} for further details.
 *
 * @author facarvalho
 */
public class PomAddDependency extends AbstractStaxArtifactPomOperation<PomAddDependency> implements AddElement<PomAddDependency>{

    // TODO
    // Add pre-validation to check, in case version was not set, if dependency
    // is managed or not. If not, fail!

    private static final String DESCRIPTION = "Add dependency %s:%s:%s to POM file %s";

    private String version;
    private String scope;

    private IfPresent ifPresent = IfPresent.Fail;

    public PomAddDependency() {
    }

    /**
     * Operation to add a new dependency to a POM file.
     * This constructor assumes this is a managed dependency, since the version
     * is not set. However, if that is not really the case, during transformation
     * this operation will fail pre-validation.
     *
     * @param groupId new dependency group id
     * @param artifactId new dependency artifact id
     */
    public PomAddDependency(String groupId, String artifactId) {
        setGroupId(groupId);
        setArtifactId(artifactId);
    }

    /**
     * Operation to add a new dependency to a POM file.
     *
     * @param groupId new dependency group id
     * @param artifactId new dependency artifact id
     * @param version new dependency artifact version
     */
    public PomAddDependency(String groupId, String artifactId, String version) {
        this(groupId, artifactId);
        setVersion(version);
    }

    /**
     * Operation to add a new dependency to a POM file.
     *
     * @param groupId new dependency group id
     * @param artifactId new dependency artifact id
     * @param version new dependency artifact version
     * @param scope new dependency artifact scope
     */
    public PomAddDependency(String groupId, String artifactId, String version, String scope) {
        this(groupId, artifactId, version);
        setScope(scope);
    }

    public PomAddDependency setVersion(String version) {
        checkForEmptyString("Version", version);
        this.version = version;
        return this;
    }

    public PomAddDependency setScope(String scope) {
        checkForEmptyString("Scope", scope);
        this.scope = scope;
        return this;
    }

    @Override
    public PomAddDependency failIfPresent() {
        ifPresent = IfPresent.Fail;
        return this;
    }

    @Override
    public PomAddDependency warnNotAddIfPresent() {
        ifPresent = IfPresent.WarnNotAdd;
        return this;
    }

    @Override
    public PomAddDependency warnButAddIfPresent() {
        ifPresent = IfPresent.WarnButAdd;
        return this;
    }

    @Override
    public PomAddDependency noOpIfPresent() {
        ifPresent = IfPresent.NoOp;
        return this;
    }

    @Override
    public PomAddDependency overwriteIfPresent() {
        ifPresent = IfPresent.Overwrite;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public String getScope() {
        return scope;
    }

    @Override
    public String getDescription() {
        return String.format(DESCRIPTION, groupId, artifactId, version, getRelativePath());
    }

    @Override
    protected TOExecutionResult pomExecution(File transformedAppFolder, TransformationContext transformationContext) throws XmlPullParserException, XMLStreamException, IOException {
        // Get the main pom file
        File pomFile = getAbsoluteFile(transformedAppFolder, transformationContext);
        int dependencyIndex= getDependencyIndex(getModel(pomFile));
        Exception warning = null;

        if (dependencyIndex != -1) {
            String message = String.format("Dependency %s:%s is already present in %s", groupId, artifactId, getRelativePath());

            switch (ifPresent) {
                case WarnNotAdd:
                    return TOExecutionResult.warning(this, message);
                case WarnButAdd:
                    warning = new TransformationOperationException(message);
                    break;
                case NoOp:
                    return TOExecutionResult.noOp(this, message);
                case Overwrite:
                    // Nothing to be done here
                    break;
                case Fail:
                    // Fail is the default
                default:
                    return TOExecutionResult.error(this, new TransformationOperationException(message));
            }
        }

        // Create new dependency
        Dependency newDependency = new Dependency();
        newDependency.setGroupId(groupId);
        newDependency.setArtifactId(artifactId);
        if (version != null) {
            newDependency.setVersion(version);
        }
        if (scope != null) {
            newDependency.setScope(scope);
        }

        XMLEventReader reader = getReader(transformedAppFolder, transformationContext);
        XMLEventWriter writer = getWriter(transformedAppFolder, transformationContext);
        XMLEvent indentation = getIndentation(transformedAppFolder, transformationContext);

        TOExecutionResult result = null;

        if(dependencyIndex != -1){
            copyUntil(reader, writer, new StartElementEventCondition("dependencies"), true);
            for (int i=0; i<dependencyIndex; i++){
                copyUntil(reader,writer,new EndElementEventCondition("dependency"), true);
            }
            skipUntil(reader,new EndElementEventCondition("dependency"));
            // now replace the dependency
            writeNewDependency(writer,indentation,newDependency);

            String details = String.format("Dependency %s:%s%s has been added to POM file %s", groupId, artifactId, (version == null ? "" : ":"+ version), pomFile.getAbsolutePath());
            result = TOExecutionResult.success(this, details);
        } else {
            //
            // There are two scenarios here
            // the <dependencies></dependencies> doesn't exists completely
            // an empty <dependencies></dependencies> exists
            copyUntil(reader,writer,new StartElementEventCondition("dependencies"),true);
            writeNewDependency(writer,indentation,newDependency);

            String details = String.format("Dependency %s:%s%s has been added to POM file %s", groupId, artifactId, (version == null ? "" : ":"+ version), pomFile.getAbsolutePath());
            result = TOExecutionResult.success(this, details);
        }
        writer.add(reader);

        if (warning != null) {
            result.addWarning(warning);
        }
        return result;
    }

    private void writeNewDependency(XMLEventWriter writer, XMLEvent indentation, Dependency newDependency) throws XMLStreamException {
        writer.add(LINE_FEED);
        writeMultiple(writer, indentation, 2);
        writer.add(eventFactory.createStartElement("", "", "dependency"));
        writer.add(LINE_FEED);
        writeMultiple(writer, indentation, 3);

        writer.add(eventFactory.createStartElement("", "", "groupId"));
        writer.add(eventFactory.createCharacters(newDependency.getGroupId()));
        writer.add(eventFactory.createEndElement("", "", "groupId"));
        writer.add(LINE_FEED);

        writeMultiple(writer, indentation, 3);
        writer.add(eventFactory.createStartElement("", "", "artifactId"));
        writer.add(eventFactory.createCharacters(newDependency.getArtifactId()));
        writer.add(eventFactory.createEndElement("", "", "artifactId"));
        writer.add(LINE_FEED);

        if(newDependency.getVersion() != null){
            writeMultiple(writer, indentation, 3);
            writer.add(eventFactory.createStartElement("", "", "version"));
            writer.add(eventFactory.createCharacters(newDependency.getVersion()));
            writer.add(eventFactory.createEndElement("", "", "version"));
            writer.add(LINE_FEED);
        }

        if (newDependency.getScope()!=null){
            writeMultiple(writer, indentation, 3);
            writer.add(eventFactory.createStartElement("", "", "scope"));
            writer.add(eventFactory.createCharacters(newDependency.getScope()));
            writer.add(eventFactory.createEndElement("", "", "scope"));
            writer.add(LINE_FEED);
        }

        writeMultiple(writer, indentation, 2);
        writer.add(eventFactory.createEndElement("", "","dependency"));
    }

}
