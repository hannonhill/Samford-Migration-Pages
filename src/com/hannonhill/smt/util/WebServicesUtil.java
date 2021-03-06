/*
 * Created on Dec 7, 2009 by Artur Tomusiak
 * 
 * Copyright(c) 2000-2009 Hannon Hill Corporation. All rights reserved.
 */
package com.hannonhill.smt.util;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import com.hannonhill.smt.ChooserType;
import com.hannonhill.smt.ContentTypeInformation;
import com.hannonhill.smt.DataDefinitionField;
import com.hannonhill.smt.Field;
import com.hannonhill.smt.MetadataSetField;
import com.hannonhill.smt.ProjectInformation;
import com.hannonhill.smt.TaskStatus;
import com.hannonhill.smt.service.FileSystem;
import com.hannonhill.smt.service.JTidy;
import com.hannonhill.smt.service.LinkRewriter;
import com.hannonhill.smt.service.Log;
import com.hannonhill.smt.service.WebServices;
import com.hannonhill.smt.service.XmlAnalyzer;
import com.hannonhill.www.ws.ns.AssetOperationService.DynamicMetadataField;
import com.hannonhill.www.ws.ns.AssetOperationService.FieldValue;
import com.hannonhill.www.ws.ns.AssetOperationService.Metadata;
import com.hannonhill.www.ws.ns.AssetOperationService.Page;
import com.hannonhill.www.ws.ns.AssetOperationService.StructuredData;
import com.hannonhill.www.ws.ns.AssetOperationService.StructuredDataAssetType;
import com.hannonhill.www.ws.ns.AssetOperationService.StructuredDataNode;
import com.hannonhill.www.ws.ns.AssetOperationService.StructuredDataType;
import com.hannonhill.www.ws.ns.AssetOperationService.XhtmlDataDefinitionBlock;

/**
 * Utility class with helper methods related to web services
 * 
 * @author Artur Tomusiak
 * @since 1.0
 */
public class WebServicesUtil
{
    public static final String HEADER_XPATH = "//div[@id=\"header-image\"]";
    public static final String ARTICLE_XPATH = "//div[@id=\"article\"]";
    public static final String ASIDE_XPATH = "//div[@class=\"aside\"]";
    public static final String SPECIAL_DATA_DEFINITION_PATH = "/Post";

    /**
     * Creates a Page object based on the information provided in the projectInformation and the actual file
     * from which the Page needs to be created.
     * 
     * @param pageFile
     * @param projectInformation
     * @return
     * @throws Exception
     */
    public static Page setupPageObject(File pageFile, ProjectInformation projectInformation) throws Exception
    {
        String path = PathUtil.truncateExtension(PathUtil.getRelativePath(pageFile, projectInformation.getXmlDirectory()));
        if (!XmlAnalyzer.allCharactersLegal(path))
            path = XmlAnalyzer.removeIllegalCharacters(path);
        String pageName = PathUtil.truncateExtension(PathUtil.getNameFromPath(path));
        String parentFolderPath = PathUtil.getParentFolderPathFromPath(path);
        String pageFileContents = JTidy.tidyContentConditionallyFullHtml(FileSystem.getFileContents(pageFile));
        if (parentFolderPath.equals(""))
            parentFolderPath = "/";

        String contentTypePath = projectInformation.getContentTypePath();
        ContentTypeInformation contentType = projectInformation.getContentTypes().get(contentTypePath);
        Set<String> metadataFieldNames = contentType.getMetadataFields().keySet();

        Page page = new Page();
        page.setContentTypePath(contentTypePath);
        page.setName(pageName);
        page.setParentFolderPath(parentFolderPath);
        page.setSiteName(projectInformation.getSiteName());
        page.setMetadata(createPageMetadata(projectInformation, pageFileContents, metadataFieldNames, projectInformation.getMigrationStatus()));

        // Create the structured data object with the tree of structured data nodes
        StructuredData structuredData = createPageStructuredData(projectInformation, pageFileContents, parentFolderPath + "/" + pageName);

        // If page uses data definition, assign it to the page object
        if (contentType.isUsesDataDefinition())
            page.setStructuredData(structuredData);
        else
        {
            // if page does not use data definition, the tree mapping should contain only a single xhtml field
            StructuredDataNode[] xhtmlNodes = structuredData.getStructuredDataNodes();
            String xhtml = null;
            if (xhtmlNodes.length == 1)
                xhtml = xhtmlNodes[0].getText();
            else if (xhtmlNodes.length == 0)
                ; // do nothing, no mappings
            else
                throw new Exception("The mappings for a page without Data Definition contains more than one field.");
            page.setXhtml(xhtml == null ? "" : xhtml);
        }

        assingSpecialBlockContent(pageFileContents, page, projectInformation);

        return page;
    }

    /**
     * Creates the page's structured data object with the values from the xmlPage uses the mappings from the
     * assetType.
     * 
     * @param projectInformation
     * @param fileContents
     * @param assetPath
     * @return
     * @throws Exception
     */
    private static StructuredData createPageStructuredData(ProjectInformation projectInformation, String fileContents, String assetPath)
            throws Exception
    {
        // Create the root group object to which all the information will be attached
        StructuredDataGroup rootGroup = new StructuredDataGroup();

        // For each field mapping assign appropriate value in structured data
        for (String xPath : projectInformation.getFieldMapping().keySet())
        {
            Field field = projectInformation.getFieldMapping().get(xPath);

            if (field == null || !(field instanceof DataDefinitionField))
                continue;

            DataDefinitionField ddField = (DataDefinitionField) field;
            String fieldValue = XmlUtil.evaluateXPathExpression(fileContents, xPath);
            if (ddField.isWysiwyg())
                fieldValue = LinkRewriter.rewriteLinksInXml(fieldValue, assetPath, projectInformation);

            assignAppropriateFieldValue(rootGroup, (DataDefinitionField) field, fieldValue, projectInformation);
        }

        // For each static value field, assign the static value in structured data
        for (Field field : projectInformation.getStaticValueMapping().keySet())
            if (field instanceof DataDefinitionField)
            {
                // Escape ampersands to make it a valid xml
                String fieldValue = projectInformation.getStaticValueMapping().get(field).replaceAll("&", "&amp;");
                assignAppropriateFieldValue(rootGroup, (DataDefinitionField) field, fieldValue, projectInformation);
            }

        populateBlockChoosers("header", HEADER_XPATH, fileContents, rootGroup, projectInformation);
        populateBlockChoosers("article", ARTICLE_XPATH, fileContents, rootGroup, projectInformation);
        populateBlockChoosers("aside", ASIDE_XPATH, fileContents, rootGroup, projectInformation);

        return convertToStructuredData(rootGroup);
    }

    /**
     * Uses an XPath to find a special block id. Reads the block and assign its contents (structured data and
     * metadata) to the page. If XPath returns more than one id, uses the first one.
     * 
     * @param fileContents
     * @param page
     * @param projectInformation
     * @throws Exception
     */
    private static void assingSpecialBlockContent(String fileContents, Page page, ProjectInformation projectInformation) throws Exception
    {
        String xPathToUse = ARTICLE_XPATH + "//ControlWidget[ControlType='Image']/ContentID/text() | " + ARTICLE_XPATH
                + "//ControlWidget[ControlType='ContentBlock']/ContentID/text()";
        List<String> blockIds = XmlUtil.evaluateXPathExpressionAsList(fileContents, xPathToUse);
        if (blockIds.size() == 0)
            return;
        String specialBlockIdField = blockIds.get(0);
        if (specialBlockIdField == null)
            return;

        String specialBlockPath = projectInformation.getBlockIdToPathMap().get(specialBlockIdField);
        if (specialBlockPath == null)
            return;

        String specialBlockId = projectInformation.getExistingCascadeXhtmlBlocks().get(specialBlockPath);
        if (specialBlockId == null)
            return;

        XhtmlDataDefinitionBlock specialBlock = WebServices.readXhtmlBlock(specialBlockId, projectInformation);

        List<StructuredDataNode> allNodes = new ArrayList<StructuredDataNode>();
        List<StructuredDataNode> pageNodes = Arrays.asList(page.getStructuredData().getStructuredDataNodes());
        List<StructuredDataNode> blockNodes = Arrays.asList(specialBlock.getStructuredData().getStructuredDataNodes());
        allNodes.addAll(pageNodes);
        allNodes.addAll(blockNodes);
        page.getStructuredData().setStructuredDataNodes(allNodes.toArray(new StructuredDataNode[0]));

        page.setMetadata(specialBlock.getMetadata());
    }

    /**
     * Populates a multiple block chooser field with given <code>identifier</code> with blocks whose names
     * come from a ContentID tags inside of an element that matches given <code>xPathExpression</code> in
     * given <code>fileContents</code>.
     * 
     * @param identifier
     * @param xPathExpression
     * @param fileContents
     * @param idToPathMap
     * @param rootGroup
     * @param projectInformation
     * @throws Exception
     */
    private static void populateBlockChoosers(String identifier, String xPathExpression, String fileContents, StructuredDataGroup rootGroup,
            ProjectInformation projectInformation) throws Exception
    {
        DataDefinitionField field = new DataDefinitionField(identifier, identifier, ChooserType.BLOCK, true, false);

        String xPathToUse = xPathExpression + "//ControlWidget[ControlType='Image']/ContentID/text() | " + xPathExpression
                + "//ControlWidget[ControlType='ContentBlock']/ContentID/text()";
        List<String> blockIds = XmlUtil.evaluateXPathExpressionAsList(fileContents, xPathToUse);
        for (String blockId : blockIds)
        {
            if (identifier.equals("article") && projectInformation.getSpecialBlockIds().contains(blockId))
                continue;

            assignAppropriateFieldValue(rootGroup, field, projectInformation.getBlockIdToPathMap().get(blockId), projectInformation);
        }

        xPathToUse = xPathExpression + "//ControlWidget[ControlType='XmlDataTransform']/Template/text()";
        List<String> xsltPaths = XmlUtil.evaluateXPathExpressionAsList(fileContents, xPathToUse);
        for (String xsltPath : xsltPaths)
            assignAppropriateFieldValue(rootGroup, field, projectInformation.getTemplateToBlockMapping().get(xsltPath), projectInformation);
    }

    /**
     * Creates the page's metadata object with the values from the xmlPage uses the mappings from the
     * assetType.
     * 
     * @param projectInformation
     * @param fileContents
     * @param availableMetadataFieldNames
     * @param taskStatus
     * @return
     * @throws Exception
     */
    private static Metadata createPageMetadata(ProjectInformation projectInformation, String fileContents, Set<String> availableMetadataFieldNames,
            TaskStatus taskStatus) throws Exception
    {
        // Create the metadata object and the list of dynamic fields
        Metadata metadata = new Metadata();
        List<DynamicMetadataField> dynamicFieldsList = new ArrayList<DynamicMetadataField>();

        // A web services bug work-around: supply all dynamic metadata field values as empty strings first
        for (String metadataFieldName : availableMetadataFieldNames)
            if (!WebServices.STANDARD_METADATA_FIELD_IDENTIFIERS.contains(metadataFieldName))
                dynamicFieldsList.add(new DynamicMetadataField(metadataFieldName, new FieldValue[]
                {
                    new FieldValue("")
                }));

        // For each field mapping assign appropriate value in metadata
        for (String xPath : projectInformation.getFieldMapping().keySet())
        {
            Field field = projectInformation.getFieldMapping().get(xPath);

            if (field == null)
                continue;

            if (field instanceof MetadataSetField)
            {
                String fieldValue = XmlUtil.evaluateXPathExpression(fileContents, xPath);
                fieldValue = trimMetadataFieldValue(field.getIdentifier(), fieldValue, taskStatus);
                assignAppropriateFieldValue(metadata, dynamicFieldsList, (MetadataSetField) field, fieldValue, taskStatus);
            }
        }

        // For each static value field, assign the static value in the metadata
        for (Field field : projectInformation.getStaticValueMapping().keySet())
            if (field instanceof MetadataSetField)
            {
                // Escape ampersands to make it a valid xml
                String fieldValue = trimMetadataFieldValue(field.getIdentifier(),
                        projectInformation.getStaticValueMapping().get(field).replaceAll("&", "&amp;"), taskStatus);
                assignAppropriateFieldValue(metadata, dynamicFieldsList, (MetadataSetField) field, fieldValue, taskStatus);
            }

        // Convert the list of dynamic field to an array and assign it to the metadata object
        metadata.setDynamicFields(dynamicFieldsList.toArray(new DynamicMetadataField[dynamicFieldsList.size()]));
        return metadata;
    }

    /**
     * Checks if given field value has more than 250 characters and if so, returns only the first 250 and
     * outputs a warning in the log.
     * 
     * @param fieldName used for logging purposes
     * @param fieldValue field value to check
     * @param taskStatus used for logging purposes
     * @return
     */
    private static String trimMetadataFieldValue(String fieldName, String fieldValue, TaskStatus taskStatus)
    {
        int maxLength = 250;

        if (WebServices.LONG_METADATA_FIELDS.contains(fieldName))
            maxLength = 65535;

        if (fieldValue == null || fieldValue.length() <= maxLength)
            return fieldValue;

        Log.add("<span style=\"color:orange;\">Cascade metadata field \"" + fieldName + "\" contains " + fieldValue.length()
                + " characters. Trimming to " + maxLength + ".</span>", taskStatus);
        return fieldValue.substring(0, maxLength);
    }

    /**
     * Assigns given fieldValue of given fieldName to metadata object if it is a standard metadata field or
     * adds it to the list of dynamicFields.
     * 
     * @param metadata
     * @param dynamicFields
     * @param field
     * @param fieldValue
     * @param taskStatus
     * @throws Exception
     */
    private static void assignAppropriateFieldValue(Metadata metadata, List<DynamicMetadataField> dynamicFields, MetadataSetField field,
            String fieldValue, TaskStatus taskStatus) throws Exception
    {
        String fieldName = field.getIdentifier();

        // If it is a standard metadata field, call the appropriate setter
        if (!field.isDynamic() && WebServices.CALENDAR_METADATA_FIELD_IDENTIFIERS.contains(fieldName))
        {
            TimeZone utc = TimeZone.getTimeZone("CST"); // Dates are parsed based on the central standard time
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-M-dd");
            sdf.setTimeZone(utc);
            try
            {
                Date date = sdf.parse(fieldValue);
                Calendar calendarValue = new GregorianCalendar(utc);
                calendarValue.setTime(date);
                Metadata.class.getMethod("set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1), Calendar.class).invoke(metadata,
                        calendarValue);
            }
            catch (ParseException e)
            {
                Log.add("<span style=\"color:orange;\">Cascade metadata field \"" + fieldName + "\" contains value " + fieldValue
                        + " that cannot be parsed into a calendar format. The proper format is YYYY-MM-DD. Skipping this field.</span>", taskStatus);
            }
        }
        else if (!field.isDynamic() && !WebServices.CALENDAR_METADATA_FIELD_IDENTIFIERS.contains(fieldName))
            Metadata.class.getMethod("set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1), String.class).invoke(metadata,
                    fieldValue);
        // If it is not a standard metadata field, add a dynamic field
        else
        {
            // Remove the previous assignment
            for (DynamicMetadataField dynamicField : dynamicFields)
                if (dynamicField.getName().equals(fieldName))
                {
                    dynamicFields.remove(dynamicField);
                    break;
                }

            // Add the current one
            dynamicFields.add(new DynamicMetadataField(fieldName, new FieldValue[]
            {
                new FieldValue(fieldValue)
            }));
        }
    }

    /**
     * Assigns given fieldValue of given fieldName as the path to the actual field identifier to structured
     * data object forming a structural tree if necessary.
     * 
     * @param rootGroup
     * @param field
     * @param fieldValue
     * @param projectInformation
     */
    private static void assignAppropriateFieldValue(StructuredDataGroup rootGroup, DataDefinitionField field, String fieldValue,
            ProjectInformation projectInformation) throws Exception
    {
        String fieldName = field.getIdentifier();
        int lastSlashIdx = fieldName.lastIndexOf('/');
        String identifier = lastSlashIdx == -1 ? fieldName : fieldName.substring(lastSlashIdx + 1);
        String groupsPath = lastSlashIdx == -1 ? "" : fieldName.substring(0, lastSlashIdx);
        StructuredDataGroup currentNode = rootGroup;

        if (!groupsPath.equals(""))
        {
            String[] groups = groupsPath.split("/");
            for (String group : groups)
            {
                StructuredDataGroup thisGroup = currentNode.getGroups().get(group);
                if (thisGroup == null)
                {
                    thisGroup = new StructuredDataGroup();
                    currentNode.getGroups().put(group, thisGroup);
                }

                currentNode = thisGroup;
            }
        }

        if (field.getChooserType() == null)
        {
            fieldValue = JTidy.tidyContentConditionally(fieldValue);
            StructuredDataNode textNode = new StructuredDataNode();
            textNode.setIdentifier(identifier);
            textNode.setText(fieldValue);
            textNode.setType(StructuredDataType.text);
            List<StructuredDataNode> textNodes = new ArrayList<StructuredDataNode>();
            textNodes.add(textNode);
            currentNode.getContentFields().put(identifier, textNodes);
        }
        else if (field.getChooserType() == ChooserType.FILE)
        {
            fieldValue = JTidy.tidyContentConditionally(fieldValue);
            String path = XmlAnalyzer.getFirstSrcAttribute(fieldValue);

            if (path == null)
                path = fieldValue;

            if (path != null && !path.trim().equals(""))
            {
                path = path.trim();
                if (WebServices.getAssetId(path, projectInformation) != null)
                {
                    StructuredDataNode fileNode = new StructuredDataNode();
                    fileNode.setIdentifier(identifier);
                    fileNode.setFilePath(path);
                    fileNode.setType(StructuredDataType.asset);
                    fileNode.setAssetType(StructuredDataAssetType.fromString("file"));
                    List<StructuredDataNode> fileNodes = new ArrayList<StructuredDataNode>();
                    fileNodes.add(fileNode);
                    currentNode.getContentFields().put(identifier, fileNodes);
                }
            }
        }
        else if (field.getChooserType() == ChooserType.BLOCK)
        {
            String path = fieldValue;
            if (path != null && !path.trim().equals(""))
            {
                path = path.trim();
                StructuredDataNode blockNode = new StructuredDataNode();
                blockNode.setIdentifier(identifier);
                blockNode.setBlockPath(path);
                blockNode.setType(StructuredDataType.asset);
                blockNode.setAssetType(StructuredDataAssetType.fromString("block"));
                List<StructuredDataNode> blockNodes = currentNode.getContentFields().get(identifier);
                if (blockNodes == null)
                {
                    blockNodes = new ArrayList<StructuredDataNode>();
                    currentNode.getContentFields().put(identifier, blockNodes);
                }

                blockNodes.add(blockNode);
            }
        }
    }

    /**
     * Converts elements from rootGroup to StructuredData object with all the ancestry (hierarchy)
     * 
     * @param rootGroup
     * @return
     */
    private static StructuredData convertToStructuredData(StructuredDataGroup rootGroup)
    {
        StructuredData sturcturedData = new StructuredData();
        sturcturedData.setStructuredDataNodes(convertToStructuredDataNodes(rootGroup));
        return sturcturedData;
    }

    /**
     * Converts group and its descendants with its contents to an array of StructuredDataNode objects
     * recursively.
     * 
     * @param group
     * @return
     */
    private static StructuredDataNode[] convertToStructuredDataNodes(StructuredDataGroup group)
    {
        List<StructuredDataNode> result = new ArrayList<StructuredDataNode>();

        for (String contentFieldIdentifier : group.getContentFields().keySet())
            for (StructuredDataNode structuredDataNode : group.getContentFields().get(contentFieldIdentifier))
                result.add(structuredDataNode);

        for (String groupIdentifier : group.getGroups().keySet())
        {
            StructuredDataGroup groupNode = group.getGroups().get(groupIdentifier);
            StructuredDataNode structuredDataNode = new StructuredDataNode();
            structuredDataNode.setIdentifier(groupIdentifier);
            structuredDataNode.setType(StructuredDataType.group);
            structuredDataNode.setStructuredDataNodes(convertToStructuredDataNodes(groupNode));
            result.add(structuredDataNode);
        }
        return result.toArray(new StructuredDataNode[0]);
    }

    /**
     * Represents a StructuredDataNode of type group. Using this instead of
     * {@link com.hannonhill.www.ws.ns.AssetOperationService.StructuredDataNode} of type group because we want
     * to deal with Maps instead of arrays for easy and fast insert and search.
     * 
     * @author Artur Tomusiak
     * @since 1.0
     */
    private static class StructuredDataGroup
    {
        // the fields in the group with their values
        private final Map<String, List<StructuredDataNode>> contentFields = new HashMap<String, List<StructuredDataNode>>();

        // other groups in the group
        private final Map<String, StructuredDataGroup> groups = new HashMap<String, StructuredDataGroup>();

        /**
         * @return Returns the groups.
         */
        public Map<String, StructuredDataGroup> getGroups()
        {
            return groups;
        }

        /**
         * @return Returns the contentFields.
         */
        public Map<String, List<StructuredDataNode>> getContentFields()
        {
            return contentFields;
        }
    }
}
