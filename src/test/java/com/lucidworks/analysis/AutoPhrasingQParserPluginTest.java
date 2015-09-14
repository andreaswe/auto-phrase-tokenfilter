package com.lucidworks.analysis;

import org.apache.lucene.analysis.util.CharArraySet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;

import static org.apache.lucene.util.Version.LUCENE_46;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;


/**
 * Unit test for {@link AutoPhrasingQParserPlugin}
 *
 * @author a.weber
 */
@RunWith(MockitoJUnitRunner.class)
public class AutoPhrasingQParserPluginTest {
    private AutoPhrasingQParserPlugin queryParser;
    private CharArraySet phraseSets;

    @Before
    public void setUp() throws Exception {
        queryParser = new AutoPhrasingQParserPlugin();
        phraseSets = new CharArraySet(LUCENE_46, Arrays.asList(
                "aa1 aa2","integrierte heiz\\-\\(klima\\-\\)steuerung", "a a", "exhaust system"), false);
    }

    @Test
    public void testIgnoreCase() throws Exception {
        final String input = "TEXT:AA0 aa2";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("TEXT:aa0 aa2"));
    }

    @Test
    public void testNoMatch() throws Exception {
        final String input = "TEXT:aa0 aa2";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("TEXT:aa0 aa2"));
    }

    @Test
    public void testSimple() throws Exception {
        final String input = "TEXT: aa1 aa2";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("TEXT:aa1xaa2"));
    }

    @Test
    public void testNarrow() throws Exception {
        final String input = "TEXT:aa1 aa2";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("TEXT:aa1xaa2"));
    }

    @Test
    public void testNarrowCase() throws Exception {
        final String input = "TEXT:aa1 Aa2";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("TEXT:aa1xaa2"));
    }

    @Test
    public void testBrackets() throws Exception {
        final String input = "(TEXT:aa1 aa2)";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("(TEXT:aa1xaa2)"));
    }

    @Test
    public void testEscapedBracketsNoMatch() throws Exception {
        final String input = "(TEXT:\\(aa1 aa2\\))";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("(TEXT:\\(aa1 aa2\\))"));
    }

    @Test
    public void testStrange() throws Exception {
        final String input = "(TEXT:integrierte heiz\\-\\(klima\\-\\)steuerung)";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("(TEXT:integriertexheiz\\-\\(klima\\-\\)steuerung)"));
    }

    @Test
    public void testSingleTokenProblem() throws Exception {
        final String input = "TEXT:a";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("TEXT:a"));
    }

    @Test
    public void testSingleTddokenProblem() throws Exception {
        final String input = "exhaust system";
        String output = queryParser.testFilter(phraseSets, input, true);
        assertThat(output, is("exhaustxsystem"));
    }

}