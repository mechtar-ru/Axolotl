package com.agent.orchestrator.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppModelTest {

    @Test
    void testAppTypeEnumContainsGame() {
        assertNotNull(AppModel.AppType.valueOf("GAME"));
    }

    @Test
    void testGameAppTypeDefaultBehavior() {
        AppModel app = new AppModel("1", "Test Game", "A game", "ws1", AppModel.AppType.GAME);
        assertEquals(AppModel.AppType.GAME, app.getAppType());
        assertEquals("GAME", app.toSchema().getAppType());
    }

    @Test
    void testFromSchemaWithGameType() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("1");
        schema.setName("Sokoban");
        schema.setAppType("GAME");
        AppModel model = AppModel.fromSchema(schema);
        assertEquals(AppModel.AppType.GAME, model.getAppType());
    }

    @Test
    void testInvalidAppTypeFallsBackToCustom() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("1");
        schema.setName("Unknown");
        schema.setAppType("INVALID_TYPE");
        AppModel model = AppModel.fromSchema(schema);
        assertEquals(AppModel.AppType.CUSTOM, model.getAppType());
    }
}
