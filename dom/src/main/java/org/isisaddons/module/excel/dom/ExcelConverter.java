/*
 *  Copyright 2014 Dan Haywood
 *
 *  Licensed under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.isisaddons.module.excel.dom;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.isis.applib.DomainObjectContainer;
import org.apache.isis.applib.annotation.Where;
import org.apache.isis.applib.filter.Filter;
import org.apache.isis.applib.filter.Filters;
import org.apache.isis.applib.services.bookmark.BookmarkService;
import org.apache.isis.applib.util.ObjectContracts;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.adapter.mgr.AdapterManager;
import org.apache.isis.core.metamodel.facets.object.viewmodel.ViewModelFacet;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.SpecificationLoader;
import org.apache.isis.core.metamodel.spec.feature.Contributed;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociation;
import org.apache.isis.core.metamodel.spec.feature.OneToOneAssociation;

class ExcelConverter {

    private static final String XLSX_SUFFIX = ".xlsx";

    @SuppressWarnings({ "unchecked", "deprecation" })
    private static final Filter<ObjectAssociation> VISIBLE_PROPERTIES = Filters.and(
            ObjectAssociation.Filters.PROPERTIES,
            ObjectAssociation.Filters.staticallyVisible(Where.STANDALONE_TABLES));

    static class RowFactory {
        private final Sheet sheet;
        private int rowNum;

        RowFactory(final Sheet sheet) {
            this.sheet = sheet;
        }

        public Row newRow() {
            return sheet.createRow((short) rowNum++);
        }
    }

    // //////////////////////////////////////

    private final SpecificationLoader specificationLoader;
    private final AdapterManager adapterManager;
    private final BookmarkService bookmarkService;

    ExcelConverter(
            final SpecificationLoader specificationLoader,
            final AdapterManager adapterManager,
            final BookmarkService bookmarkService) {
        this.specificationLoader = specificationLoader;
        this.adapterManager = adapterManager;
        this.bookmarkService = bookmarkService;
    }

    // //////////////////////////////////////

    <T> File toFile(final Class<T> cls, final List<T> domainObjects) throws IOException {

        final ObjectSpecification objectSpec = specificationLoader.loadSpecification(cls);

        final List<ObjectAdapter> adapters = Lists.transform(domainObjects, ObjectAdapter.Functions.adapterForUsing(adapterManager));

        @SuppressWarnings("deprecation")
        final List<? extends ObjectAssociation> propertyList = objectSpec.getAssociations(VISIBLE_PROPERTIES);

        final Workbook wb = new XSSFWorkbook();
        final String sheetName = cls.getSimpleName();
        final File tempFile = File.createTempFile(ExcelConverter.class.getName(), sheetName + XLSX_SUFFIX);

        final FileOutputStream fos = new FileOutputStream(tempFile);
        final Sheet sheet = wb.createSheet(sheetName);

        final ExcelConverter.RowFactory rowFactory = new RowFactory(sheet);
        final Row headerRow = rowFactory.newRow();

        // header row
        int i = 0;
        for (final ObjectAssociation property : propertyList) {
            final Cell cell = headerRow.createCell((short) i++);
            cell.setCellValue(property.getName());
        }

        final CellMarshaller cellMarshaller = newCellMarshaller(wb);

        // detail rows
        for (final ObjectAdapter objectAdapter : adapters) {
            final Row detailRow = rowFactory.newRow();
            i = 0;
            for (final ObjectAssociation oa : propertyList) {
                final Cell cell = detailRow.createCell((short) i++);
                final OneToOneAssociation otoa = (OneToOneAssociation) oa;
                cellMarshaller.setCellValue(objectAdapter, otoa, cell);
            }
        }

        // freeze panes
        sheet.createFreezePane(0, 1);

        wb.write(fos);
        fos.close();
        return tempFile;
    }

    <T> List<T> fromBytes(
            final Class<T> cls,
            final byte[] bs,
            final DomainObjectContainer container) throws IOException, InvalidFormatException {

        final List<T> importedItems = Lists.newArrayList();

        final ObjectSpecification objectSpec = specificationLoader.loadSpecification(cls);
        final ViewModelFacet viewModelFacet = objectSpec.getFacet(ViewModelFacet.class);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(bs)) {
            final Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(bais);
            final CellMarshaller cellMarshaller = this.newCellMarshaller(wb);

            final Sheet sheet = wb.getSheetAt(0);

            boolean header = true;
            final Map<Integer, Property> propertyByColumn = Maps.newHashMap();

            for (final Row row : sheet) {
                if (header) {
                    for (final Cell cell : row) {
                        if (cell.getCellType() != Cell.CELL_TYPE_BLANK) {
                            final int columnIndex = cell.getColumnIndex();
                            final String propertyName = cellMarshaller.getStringCellValue(cell);
                            final OneToOneAssociation property = getAssociation(objectSpec, propertyName);
                            if (property != null) {
                                final Class<?> propertyType = property.getSpecification().getCorrespondingClass();
                                propertyByColumn.put(columnIndex, new Property(propertyName, property, propertyType));
                            }
                        }
                    }
                    header = false;
                } else {
                    // detail
                    try {

                        // Let's require at least one column to be not null for detecting a blank row.
                        // Excel can have physical rows with cells empty that it seem do not existent for the user.
                        ObjectAdapter templateAdapter = null;
                        T imported = null;
                        for (final Cell cell : row) {
                            final int columnIndex = cell.getColumnIndex();
                            final Property property = propertyByColumn.get(columnIndex);
                            if (property != null) {
                                final OneToOneAssociation otoa = property.getOneToOneAssociation();
                                final Object value = cellMarshaller.getCellValue(cell, otoa);
                                if (value != null) {
                                    if (imported == null) {
                                        // copy the row into a new object
                                        imported = container.newTransientInstance(cls);
                                        templateAdapter = this.adapterManager.adapterFor(imported);
                                    }
                                    final ObjectAdapter valueAdapter = this.adapterManager.adapterFor(value);
                                    otoa.set(templateAdapter, valueAdapter);
                                }
                            } else {
                                // not expected; just ignore.
                            }
                        }

                        if (imported != null) {
                            if (viewModelFacet != null) {
                                // if there is a view model, then use the imported object as a template
                                // in order to create a regular view model.
                                final String memento = viewModelFacet.memento(imported);
                                final T viewModel = container.newViewModelInstance(cls, memento);
                                importedItems.add(viewModel);
                            } else {
                                // else, just return the imported items as simple transient instances.
                                importedItems.add(imported);
                            }
                        }
                    } catch (final Exception e) {
                        bais.close();
                        throw new ExcelService.Exception(String.format("Error processing Excel row nr. %d. Message: %s", row.getRowNum(), e.getMessage()), e);
                    }
                }
            }
        }

        return importedItems;
    }

    private OneToOneAssociation getAssociation(final ObjectSpecification objectSpec, final String propertyName) {
        final List<ObjectAssociation> associations = objectSpec.getAssociations(Contributed.INCLUDED);
        for (final ObjectAssociation association : associations) {
            if (propertyName.equals(association.getName()) && association instanceof OneToOneAssociation) {
                return (OneToOneAssociation) association;
            }
        }
        return null;
    }

    static class Property {
        private final String name;
        private final Class<?> type;
        private final OneToOneAssociation property;
        private Object currentValue;

        public Property(final String name, final OneToOneAssociation property, final Class<?> type) {
            this.name = name;
            this.property = property;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public OneToOneAssociation getOneToOneAssociation() {
            return property;
        }

        public Class<?> getType() {
            return type;
        }

        public Object getCurrentValue() {
            return currentValue;
        }

        public void setCurrentValue(final Object currentValue) {
            this.currentValue = currentValue;
        }

        @Override
        public String toString() {
            return ObjectContracts.toString(this, "name,type,currentValue");
        }
    }

    @SuppressWarnings("unused")
    private void autoSize(final Sheet sh, final int numProps) {
        for (int prop = 0; prop < numProps; prop++) {
            sh.autoSizeColumn(prop);
        }
    }

    // //////////////////////////////////////

    protected CellMarshaller newCellMarshaller(final Workbook wb) {
        final CellStyle dateCellStyle = createDateFormatCellStyle(wb);
        final CellMarshaller cellMarshaller = new CellMarshaller(bookmarkService, dateCellStyle);
        return cellMarshaller;
    }

    protected CellStyle createDateFormatCellStyle(final Workbook wb) {
        final CreationHelper createHelper = wb.getCreationHelper();
        final short dateFormat = createHelper.createDataFormat().getFormat("yyyy-mm-dd");
        final CellStyle dateCellStyle = wb.createCellStyle();
        dateCellStyle.setDataFormat(dateFormat);
        return dateCellStyle;
    }

}
