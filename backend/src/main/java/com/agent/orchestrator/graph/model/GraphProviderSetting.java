package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("ProviderSetting")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphProviderSetting {

    @Id
    @Property("providerName")
    private String providerName;

    @Property("apiKey")
    private String apiKey;

    @Property("baseUrl")
    private String baseUrl;

    @Property("defaultModel")
    private String defaultModel;

    @Property("updatedAt")
    private String updatedAt;

    @Property("disabledModels")
    private List<String> disabledModels;

    @Property("models")
    private List<String> models;

    @Property("projectsFolder")
    private String projectsFolder;







    public List<String> getDisabledModels() { return disabledModels; }

    public List<String> getModels() { return models; }

}
