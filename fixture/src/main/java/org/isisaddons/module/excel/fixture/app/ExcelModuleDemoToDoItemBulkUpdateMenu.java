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
package org.isisaddons.module.excel.fixture.app;

import java.math.BigDecimal;
import javax.annotation.PostConstruct;
import org.isisaddons.module.excel.dom.ExcelService;
import org.isisaddons.module.excel.fixture.dom.ExcelModuleDemoToDoItem;
import org.isisaddons.module.excel.fixture.dom.ExcelModuleDemoToDoItem.Category;
import org.isisaddons.module.excel.fixture.dom.ExcelModuleDemoToDoItem.Subcategory;
import org.joda.time.LocalDate;
import org.apache.isis.applib.DomainObjectContainer;
import org.apache.isis.applib.annotation.Action;
import org.apache.isis.applib.annotation.DomainService;
import org.apache.isis.applib.annotation.MemberOrder;
import org.apache.isis.applib.annotation.SemanticsOf;
import org.apache.isis.applib.services.bookmark.Bookmark;
import org.apache.isis.applib.services.bookmark.BookmarkService;
import org.apache.isis.applib.services.memento.MementoService;
import org.apache.isis.applib.services.memento.MementoService.Memento;

@DomainService
public class ExcelModuleDemoToDoItemBulkUpdateMenu {

    @PostConstruct
    public void init() {
        if(excelService == null) {
            throw new IllegalStateException("Require ExcelService to be configured");
        }
    }

    // //////////////////////////////////////
    // bulk update manager (action)
    // //////////////////////////////////////

    @Action(
            semantics = SemanticsOf.IDEMPOTENT
    )
    @MemberOrder(name="ToDos", sequence="90.1")
    public ExcelModuleDemoToDoItemBulkUpdateManager bulkUpdateManager() {
        ExcelModuleDemoToDoItemBulkUpdateManager template = new ExcelModuleDemoToDoItemBulkUpdateManager();
        template.setFileName("toDoItems.xlsx");
        template.setCategory(Category.Domestic);
        template.setSubcategory(Subcategory.Shopping);
        template.setComplete(false);
        return newBulkUpdateManager(template);
    }


    // //////////////////////////////////////
    // memento for manager
    // //////////////////////////////////////
    
    String mementoFor(final ExcelModuleDemoToDoItemBulkUpdateManager tdieim) {
        final Memento memento = mementoService.create();
        memento.set("fileName", tdieim.getFileName());
        memento.set("category", tdieim.getCategory());
        memento.set("subcategory", tdieim.getSubcategory());
        memento.set("completed", tdieim.isComplete());
        return memento.asString();
    }
    
    void initOf(final String mementoStr, final ExcelModuleDemoToDoItemBulkUpdateManager manager) {
        final Memento memento = mementoService.parse(mementoStr);
        manager.setFileName(memento.get("fileName", String.class));
        manager.setCategory(memento.get("category", Category.class));
        manager.setSubcategory(memento.get("subcategory", Subcategory.class));
        manager.setComplete(memento.get("completed", boolean.class));
    }

    ExcelModuleDemoToDoItemBulkUpdateManager newBulkUpdateManager(ExcelModuleDemoToDoItemBulkUpdateManager manager) {
        return container.newViewModelInstance(ExcelModuleDemoToDoItemBulkUpdateManager.class, mementoFor(manager));
    }
    
    // //////////////////////////////////////
    // memento for line item
    // //////////////////////////////////////
    
    String mementoFor(ExcelModuleDemoToDoItemBulkUpdateLineItem lineItem) {
        final Memento memento = mementoService.create();
        memento.set("toDoItem", bookmarkService.bookmarkFor(lineItem.getToDoItem()));
        memento.set("description", lineItem.getDescription());
        memento.set("category", lineItem.getCategory());
        memento.set("subcategory", lineItem.getSubcategory());
        memento.set("cost", lineItem.getCost());
        memento.set("complete", lineItem.isComplete());
        memento.set("dueBy", lineItem.getDueBy());
        memento.set("notes", lineItem.getNotes());
        memento.set("ownedBy", lineItem.getOwnedBy());
        return memento.asString();
    }

    void init(String mementoStr, ExcelModuleDemoToDoItemBulkUpdateLineItem lineItem) {
        final Memento memento = mementoService.parse(mementoStr);
        lineItem.setToDoItem(bookmarkService.lookup(memento.get("toDoItem", Bookmark.class), ExcelModuleDemoToDoItem.class));
        lineItem.setDescription(memento.get("description", String.class));
        lineItem.setCategory(memento.get("category", Category.class));
        lineItem.setSubcategory(memento.get("subcategory", Subcategory.class));
        lineItem.setCost(memento.get("cost", BigDecimal.class));
        lineItem.setComplete(memento.get("complete", boolean.class));
        lineItem.setDueBy(memento.get("dueBy", LocalDate.class));
        lineItem.setNotes(memento.get("notes", String.class));
        lineItem.setOwnedBy(memento.get("ownedBy", String.class));
    }
    
    ExcelModuleDemoToDoItemBulkUpdateLineItem newLineItem(ExcelModuleDemoToDoItemBulkUpdateLineItem lineItem) {
        return container.newViewModelInstance(ExcelModuleDemoToDoItemBulkUpdateLineItem.class, mementoFor(lineItem));
    }


    // //////////////////////////////////////
    // Injected Services
    // //////////////////////////////////////

    @javax.inject.Inject
    private DomainObjectContainer container;

    @javax.inject.Inject
    private ExcelService excelService;

    @javax.inject.Inject
    private BookmarkService bookmarkService;
    
    @javax.inject.Inject
    private MementoService mementoService;

}
