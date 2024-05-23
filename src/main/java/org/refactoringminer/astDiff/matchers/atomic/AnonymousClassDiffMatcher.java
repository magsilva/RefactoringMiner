package org.refactoringminer.astDiff.matchers.atomic;

import com.github.gumtreediff.tree.Tree;
import gr.uom.java.xmi.decomposition.UMLOperationBodyMapper;
import gr.uom.java.xmi.diff.UMLAnonymousClassDiff;
import org.refactoringminer.astDiff.matchers.ExtendedMultiMappingStore;
import org.refactoringminer.astDiff.matchers.OptimizationData;

import java.util.List;

/* Created by pourya on 2024-05-22*/
public class AnonymousClassDiffMatcher extends OptimizationAwareMatcher {

    private final UMLAnonymousClassDiff anonymousClassDiff;

    public AnonymousClassDiffMatcher(OptimizationData optimizationData, UMLAnonymousClassDiff anonymousClassDiff) {
        super(optimizationData);
        this.anonymousClassDiff = anonymousClassDiff;
    }

    @Override
    public void match(Tree srcTree, Tree dstTree, ExtendedMultiMappingStore mappingStore) {
        processAnonymousClassDiff(srcTree, dstTree, anonymousClassDiff, mappingStore);
    }

    private void processAnonymousClassDiff(Tree srcTree, Tree dstTree, UMLAnonymousClassDiff anonymousClassDiff, ExtendedMultiMappingStore mappingStore) {
        List<UMLOperationBodyMapper> operationBodyMapperList = anonymousClassDiff.getOperationBodyMapperList();
        for (UMLOperationBodyMapper umlOperationBodyMapper : operationBodyMapperList) {
            new MethodMatcher(optimizationData, umlOperationBodyMapper).match(srcTree,dstTree,mappingStore);
        }
    }
}
