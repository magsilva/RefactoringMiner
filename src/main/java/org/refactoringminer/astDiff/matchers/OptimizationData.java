package org.refactoringminer.astDiff.matchers;

import gr.uom.java.xmi.decomposition.AbstractCodeMapping;

import java.util.List;

public class OptimizationData {
    private List<AbstractCodeMapping> lastStepMappings;
    private ExtendedMultiMappingStore subtreeMappings;

    public List<AbstractCodeMapping> getLastStepMappings() {
        return lastStepMappings;
    }

    public ExtendedMultiMappingStore getSubtreeMappings() {
        return subtreeMappings;
    }

    public OptimizationData(List<AbstractCodeMapping> lastStepMappings, ExtendedMultiMappingStore subtreeMappings) {
        this.lastStepMappings = lastStepMappings;
        this.subtreeMappings = subtreeMappings;
    }

}
