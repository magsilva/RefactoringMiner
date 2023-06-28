package org.refactoringminer.astDiff.tests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.joshka.junit.json.params.JsonFileSource;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.refactoringminer.astDiff.actions.ASTDiff;
import org.refactoringminer.astDiff.utils.CaseInfo;
import org.refactoringminer.astDiff.utils.MappingExportModel;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.fail;
import static org.refactoringminer.astDiff.utils.UtilMethods.*;

/**
 * @author  Pourya Alikhani Fard pouryafard75@gmail.com
 */
public class Defects4JPerfectDiffTest {
    private static final String dir = getDefects4jMappingPath();
    @ParameterizedTest(name= "{index}: File: {2}, Repo: {0}, Commit: {1}")
    @JsonFileSource(resources = "/astDiff/defects4j/cases-problematic.json")
    public void testSubTreeMappings(@ConvertWith(CaseInfo.CaseInfoConverter.class) CaseInfo info) throws JsonProcessingException {
        File mappingsDirFile = new File(getFinalFolderPath(dir, info.getRepo(), info.getCommit()));
        String[] files = mappingsDirFile.list();
        List<String> expectedFilesList = new ArrayList<>(List.of(Objects.requireNonNull(files)));
        String projectDir = info.getRepo();
        String bugID = info.getCommit();
        Set<ASTDiff> astDiffs = new GitHistoryRefactoringMinerImpl().diffAtDirectories(
                Path.of(getDefect4jBeforeDir(projectDir, bugID)),
                Path.of(getDefect4jAfterDir(projectDir, bugID)));

        for (ASTDiff astDiff : astDiffs) {
            String finalFilePath = getFinalFilePath(astDiff, dir, info.getRepo(), info.getCommit());
            String calculated = MappingExportModel.exportString(astDiff.getAllMappings());
            String msg = String.format("Failed for %s/commit/%s , srcFileName: %s",info.getRepo().replace(".git",""),info.getCommit(),astDiff.getSrcPath());
            String expected = null;
            try {
                expected = FileUtils.readFileToString(new File(finalFilePath), "utf-8");
                assertEquals(expected.length(), calculated.length(), msg);
            } catch (IOException e) {
                fail("File not found: " + finalFilePath + " for " + info.getRepo() + "/commit/" + info.getCommit() + " , srcFileName: " + astDiff.getSrcPath());
            }
            assertEquals(calculated, expected, msg);
            expectedFilesList.remove(getFileNameFromSrcDiff(astDiff.getSrcPath()));
        }
        for (String expectedButNotGeneratedFile : expectedFilesList) {
            String expectedDiffName = getSrcASTDiffFromFile(expectedButNotGeneratedFile);
            fail(expectedDiffName);
        }
    }
}
