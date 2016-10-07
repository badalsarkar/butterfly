package com.paypal.butterfly.core;

import com.paypal.butterfly.core.exception.InternalException;
import com.paypal.butterfly.extensions.api.*;
import com.paypal.butterfly.extensions.api.exception.TransformationUtilityException;
import com.paypal.butterfly.extensions.api.utilities.MultipleOperations;
import com.paypal.butterfly.facade.Configuration;
import com.paypal.butterfly.facade.exception.TransformationException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The transformation engine in charge of
 * applying transformations
 *
 * @author facarvalho
 */
@Component
public class TransformationEngine {

    private static final Logger logger = LoggerFactory.getLogger(TransformationEngine.class);

    private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");

    public void perform(Transformation transformation) throws TransformationException {
        if(logger.isDebugEnabled()) {
            logger.debug("Requested transformation: {}", transformation);
        }

        File transformedAppFolder = prepareOutputFolder(transformation);

        TransformationTemplate template = transformation.getTemplate();
        logger.info("Beginning transformation ({} operations to be performed)", template.getOperationsCount());
        AtomicInteger operationsExecutionOrder = new AtomicInteger(1);

        TransformationContextImpl transformationContext = new TransformationContextImpl();

        MultipleOperations multipleOperations;
        TransformationUtility utility;
        PerformResult result;
        for(Object transformationUtilityObj: template.getTransformationUtilitiesList()) {
            utility = (TransformationUtility) transformationUtilityObj;
            if(transformationUtilityObj instanceof MultipleOperations) {
                multipleOperations = (MultipleOperations) transformationUtilityObj;
                result = perform(multipleOperations, transformedAppFolder, transformationContext, operationsExecutionOrder);
                // FIXME saving multiple operation results need to be properly taken care of
            } else {
                result = perform(utility, transformedAppFolder, transformationContext, operationsExecutionOrder, null);
            }
            if (utility.isSaveResult()) {
                // Saving the whole perform result, which is different from the value that resulted from the utility execution,
                // saved in processUtilityExecutionResult

                transformationContext.putResult(((TransformationUtility) transformationUtilityObj).getName(), result);
            }
        }

        logger.info("Transformation has been completed");
    }

    // TODO how to deal with results here???
    // First of all, Multiple operations must be converted to TO, instead of TU
    private PerformResult perform(MultipleOperations multipleOperations, File transformedAppFolder, TransformationContextImpl transformationContext, AtomicInteger outterOpExecOrder) throws TransformationException {
        List<TransformationOperation> operations;
        PerformResult result;
        try {
            result = multipleOperations.perform(transformedAppFolder, transformationContext);

            // TODO what if the result type is not EXECUTION_RESULT?
            operations = (List<TransformationOperation>) ((TUExecutionResult) result.getExecutionResult()).getValue();
        } catch (TransformationUtilityException e) {

            // TODO what about abortOnFailure for MultipleOperations?

            logger.error("*** Transformation will be aborted due to failure in {}  ***", multipleOperations.getName());
            logger.error("*** Description: {}", multipleOperations.getDescription());
            logger.error("*** Cause: " + e.getCause());

            throw new TransformationException(multipleOperations.getName() + " failed when performing transformation", e);
        }

        logger.info("\t{}\t - Executing {} over {} files", outterOpExecOrder.intValue(), multipleOperations.getTemplateOperation().getName(), operations.size());

        AtomicInteger innerOpExecOrder = new AtomicInteger(1);
        for(TransformationOperation operation : operations) {
            perform(operation, transformedAppFolder, transformationContext, innerOpExecOrder, outterOpExecOrder.intValue());
            // FIXME the transformation context is not having a chance to save the result of every one of these. Only the template operation is having its result saved
        }

        outterOpExecOrder.incrementAndGet();

        return result;
    }

    private PerformResult perform(TransformationUtility utility, File transformedAppFolder, TransformationContextImpl transformationContext, AtomicInteger operationsExecutionOrder, Integer outterOrder) throws TransformationException {
        boolean isTO = utility instanceof TransformationOperation;
        String order = "-";
        if (isTO) {
            if(outterOrder != null) {
                order = String.format("%d.%d", outterOrder, operationsExecutionOrder.get());
            } else {
                order = String.valueOf(operationsExecutionOrder.get());
            }
        }
        PerformResult result = null;
        try {
            result = utility.perform(transformedAppFolder, transformationContext);

            switch (result.getType()) {
                case SKIPPED_CONDITION:
                case SKIPPED_DEPENDENCY:
                    if (isTO || logger.isDebugEnabled()) {
                        logger.info("\t{}\t - {}", order, result.getDetails());
                    }
                    break;
                case EXECUTION_RESULT:
                    if (isTO) {
                        processOperationExecutionResult(utility, result, order);
                    } else {
                        processUtilityExecutionResult(utility, result, transformationContext);
                    }
                    break;
                case ERROR:
                    if(logger.isDebugEnabled()) {
                        logger.error(utility.getName() + " has failed due to the exception below", result.getException());
                    }
                    logger.error("\t{}\t - '{}' has failed. See debug logs for further details.", order, utility.getName());
                    break;
                default:
                    logger.error("\t{}\t - '{}' has resulted in an unexpected perform result type {}", order, utility.getName(), result.getType().name());
                    break;
            }
        } catch (TransformationUtilityException e) {
            result = PerformResult.error(utility, e);
            if (utility.abortOnFailure()) {
                logger.error("*** Transformation will be aborted due to failure in {}  ***", utility.getName());
                logger.error("*** Description: {}", utility.getDescription());
                logger.error("*** Cause: " + e.getCause());

                throw new TransformationException(utility.getName() + " failed when performing transformation", e);
            } else {
                if(logger.isDebugEnabled()) {
                    logger.debug(utility.getName() + " has failed due to the exception below", e);
                }
                logger.error("\t{}\t - '{}' has failed. See debug logs for further details.", order, utility.getName());
            }
        } finally {
            if (isTO) operationsExecutionOrder.incrementAndGet();
        }
        return result;
    }

    private void processOperationExecutionResult(TransformationUtility utility, PerformResult result, String order) {
        TOExecutionResult executionResult = (TOExecutionResult) result.getExecutionResult();
        switch (executionResult.getType()) {
            case SUCCESS:
                logger.info("\t{}\t - {}", order, executionResult.getDetails());
                break;
            case NO_OP:
                logger.warn("\t{}\t - {}", order, executionResult.getDetails());
                break;
            case WARNING:
                processExecutionResultWarningType(utility, result, executionResult, order);
                break;
            case ERROR:
                processExecutionResultErrorType(utility, executionResult, order);
                break;
            default:
                processExecutionResultUnknownType(utility, executionResult, order);
                break;
        }
    }

    private void processUtilityExecutionResult(TransformationUtility utility, PerformResult result, TransformationContextImpl transformationContext) {
        TUExecutionResult executionResult = (TUExecutionResult) result.getExecutionResult();
        if (utility.isSaveResult()) {
            // Saving the value that resulted from the utility execution, which is different from the whole perform result
            // object saved in the main perform method

            String key = (utility.getContextAttributeName() != null ? utility.getContextAttributeName() : utility.getName());
            transformationContext.put(key, executionResult.getValue());
        }
        switch (((TUExecutionResult) result.getExecutionResult()).getType()) {
            case NULL:
                if (utility.isSaveResult() && logger.isDebugEnabled()) {
                    logger.warn("\t-\t - {} ({}) has returned NULL", utility, utility.getName());
                }
                break;
            case VALUE:
                logger.debug("\t-\t - {} ({})", utility, utility.getName());
                break;
            case WARNING:
                processExecutionResultWarningType(utility, result, executionResult, "-");
                break;
            case ERROR:
                processExecutionResultErrorType(utility, executionResult, "-");
                break;
            default:
                processExecutionResultUnknownType(utility, executionResult, "-");
                break;
        }
    }

    private void processExecutionResultWarningType(TransformationUtility utility, PerformResult result, ExecutionResult executionResult, String order) {
        logger.warn("\t{}\t - '{}' has successfully been executed, but it has warnings, see debug logs for further details", order, utility.getName());
        if (logger.isDebugEnabled()) {
            if (result.getWarnings().size() == 0) {
                logger.warn("\t\t\t * Warning message: {}", result.getDetails());
            } else {
                logger.warn("\t\t\t * Execution details: {}", executionResult.getDetails());
                logger.warn("\t\t\t * Warnings (see debug logs for further details):");
                for (Object warning : executionResult.getWarnings()) {
                    String message = String.format("\t\t\t\t - %s: %s", warning.getClass().getName(), ((Exception) warning).getMessage());
                    logger.warn(message, warning);
                }
            }
        }
    }

    private void processExecutionResultErrorType(TransformationUtility utility, ExecutionResult executionResult, String order) {
        if(logger.isDebugEnabled()) {
            logger.error(utility.getName() + " has failed due to the exception below", executionResult.getException());
        }
        logger.error("\t{}\t - '{}' has failed. See debug logs for further details.", order, utility.getName());
    }

    private void processExecutionResultUnknownType(TransformationUtility utility, ExecutionResult executionResult, String order) {
        logger.error("\t{}\t - '{}' has resulted in an unexpected execution result type {}", order, utility.getName(), executionResult.getType());
    }

    private File prepareOutputFolder(Transformation transformation) {
        logger.debug("Preparing output folder");

        Application application =  transformation.getApplication();
        Configuration configuration =  transformation.getConfiguration();

        logger.info("Original application folder: " + application.getFolder());

        File originalAppParent = application.getFolder().getParentFile();
        String transformedAppFolderName = application.getFolder().getName() + "-transformed-" + getCurrentTimeStamp();

        File transformedAppFolder;

        if(configuration.getOutputFolder() != null) {
            if(!configuration.getOutputFolder().exists()) {
                throw new IllegalArgumentException("Invalid output folder (" + configuration.getOutputFolder() + ")");
            }
            transformedAppFolder = new File(configuration.getOutputFolder().getAbsolutePath() + File.separator + transformedAppFolderName);
        } else {
            transformedAppFolder = new File(originalAppParent.getAbsolutePath() + File.separator + transformedAppFolderName);
        }

        logger.info("Transformed application folder: " + transformedAppFolder);

        transformation.setTransformedApplicationLocation(transformedAppFolder);

        boolean bDirCreated = transformedAppFolder.mkdir();
        if(bDirCreated){
            try {
                FileUtils.copyDirectory(application.getFolder(), transformedAppFolder);
            } catch (IOException e) {
                String exceptionMessage = String.format(
                        "An error occurred when preparing the transformed application folder (%s). Check also if the original application folder (%s) is valid",
                        transformedAppFolder, application.getFolder());
                logger.error(exceptionMessage, e);
                throw new InternalException(exceptionMessage, e);
            }
            logger.debug("Transformed application folder is prepared");
        }else{
            String exceptionMessage = String.format("Transformed application folder (%s) could not be created", transformedAppFolder);
            InternalException ie  = new InternalException(exceptionMessage);
            logger.error(exceptionMessage, ie);
            throw ie;
        }
        return transformedAppFolder;
    }

    @SuppressFBWarnings("STCAL_INVOKE_ON_STATIC_DATE_FORMAT_INSTANCE")
    public static String getCurrentTimeStamp() {
        return simpleDateFormat.format(new Date());
    }

}
