package com.lucidworks.analysis;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.analysis.util.ResourceLoaderAware;
import org.apache.lucene.analysis.util.WordlistLoader;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static org.apache.commons.lang.StringUtils.trim;
import static org.apache.lucene.util.Version.LUCENE_46;


public class AutoPhrasingQParserPlugin extends QParserPlugin implements ResourceLoaderAware {

  private static final Logger Log = LoggerFactory.getLogger( AutoPhrasingQParserPlugin.class );
  private static final String NOT_ESCAPED = "(?<!\\\\)";
  private CharArraySet phraseSets;
  private String phraseSetFiles;
  private final Pattern FIELD_PATTERN= Pattern.compile(NOT_ESCAPED + ":$");

  /**
   * Pattern matching SOLR special characters that are not wildcards.
   */
  private static final Pattern ESCAPE_PATTERN = Pattern.compile(escapeNonWildcardRegex(new char[]{
          '\\', '+', '-', '!', '(', ')', ':', '^',
          '[', ']', '\"', '{', '}', '~', '|', '&',
          ';', '/', '\t', '\r', '\n', '*'}));

  private String parserImpl = "lucene";

  private char replaceWhitespaceWith = 'x';  // preserves stemming

  private boolean ignoreCase = true;

  @Override
  public void init( NamedList initArgs ) {
    Log.info( "init ..." );
    SolrParams params = SolrParams.toSolrParams(initArgs);
    phraseSetFiles = params.get( "phrases" );

    String pImpl = params.get( "defType" );
    if (pImpl != null) {
      parserImpl = pImpl;
    }

    String replaceWith = params.get( "replaceWhitespaceWith" );
    if (replaceWith != null && replaceWith.length() > 0) {
      replaceWhitespaceWith = replaceWith.charAt(0);
    }

    String ignoreCaseSt = params.get("ignoreCase");
    if (ignoreCaseSt != null && ignoreCaseSt.equalsIgnoreCase( "false" )) {
      ignoreCase = false;
    }
  }

  @Override
  public QParser createParser( String qStr, SolrParams localParams, SolrParams params,
			                   SolrQueryRequest req) {
    Log.info( "createParser" );
    ModifiableSolrParams modparams = new ModifiableSolrParams( params );
    String modQ = filter(qStr);

    modparams.set( "q", modQ );
    return req.getCore().getQueryPlugin( parserImpl )
                        .createParser(modQ, localParams, modparams, req);
  }

  private String filter( String qStr ) {
    // 1) collapse " :" to ":" to protect field names
    // 2) expand ":" to ": " to free terms from field names
    // 3) expand "+" to "+ " to free terms from "+" operator
    // 4) expand "-" to "- " to free terms from "-" operator
    // 5) expand ")" to " )" and "(" to "( " to free terms from (nonescaped bracket) brackets
	// 5) Autophrase with whitespace tokenizer
	// 6) collapse "+ " and "- " to "+" and "-" to glom operators.

    // 1)
    String query = qStr;
    query = query.replaceAll( "\\s:", ":" );

    // 2)
    query = query.replaceAll(NOT_ESCAPED + ":", ": " );

    query = query.replaceAll(NOT_ESCAPED + "\\+", "+ " );
    query = query.replaceAll(NOT_ESCAPED + "\\-", "- " );

    query = query.replaceAll(NOT_ESCAPED + "\\)", " \\)" );
    query = query.replaceAll(NOT_ESCAPED +  "\\(", "\\( " );

    if (ignoreCase) {
      query = query.replaceAll( "AND", "&&" );
      query = query.replaceAll( "OR", "||" );
    }

    try {
      query = autophrase( query );
    }
    catch (IOException ioe ) {  }

    query = query.replaceAll( NOT_ESCAPED + "\\+ ", "+" );
    query = query.replaceAll( NOT_ESCAPED + "\\- ", "-" );

    if (ignoreCase) {
      query = query.replaceAll( "&&", "AND" );
      query = query.replaceAll( "\\|\\|", "OR" );
    }

    query = query.replaceAll(NOT_ESCAPED + ": ", ":" );

    query = query.replaceAll( " \\)", "\\)" );
    query = query.replaceAll( "\\( ", "\\(" );

    return query;
  }

  public String testFilter(CharArraySet phraseSets, String input, boolean ignoreCase) {
    this.ignoreCase = ignoreCase;
    this.phraseSets = phraseSets;
    return filter(input);
  }

  private String autophrase( String input ) throws IOException {
    if (ignoreCase) {
      StringBuffer lowerCaseBuff = new StringBuffer( );
      String[] tokens = input.split(" ");
      for (String token : tokens) {
        if (FIELD_PATTERN.matcher(token).find()) {
          lowerCaseBuff.append(token);
        } else {
          lowerCaseBuff.append(token.toLowerCase());
        }
        lowerCaseBuff.append(" ");
      }
      input = lowerCaseBuff.toString();
    }
    TokenStream ts = new WhitespaceTokenizer(LUCENE_46,  new StringReader( input ));


    AutoPhrasingTokenFilter aptf = new AutoPhrasingTokenFilter( ts, phraseSets, false );
    aptf.setReplaceWhitespaceWith( new Character( replaceWhitespaceWith ) );
    CharTermAttribute term = aptf.addAttribute(CharTermAttribute.class);
    aptf.reset();

    StringBuffer strbuf = new StringBuffer( );
    while( aptf.incrementToken( )) {
      strbuf.append( term.toString()).append(" " );
    }

    return trim(strbuf.toString());
  }

  @Override
  public void inform(ResourceLoader loader) throws IOException {
    if (phraseSetFiles != null) {
      phraseSets = escapeSpecialCharacters(getWordSet(loader, phraseSetFiles, true));
    }
  }

  private CharArraySet escapeSpecialCharacters(CharArraySet wordSet) {
    CharArraySet escapedSet = new CharArraySet(LUCENE_46, wordSet.size(), false);
    Iterator<Object> phraseIt = wordSet.iterator();
    while (phraseIt != null && phraseIt.hasNext() ) {
      char[] phrase = (char[]) phraseIt.next();
      escapedSet.add(ESCAPE_PATTERN.matcher(new String(phrase)).replaceAll("\\\\$1").toCharArray());
    }
    return escapedSet;
  }

  private CharArraySet getWordSet( ResourceLoader loader,
		                           String wordFiles, boolean ignoreCase)
		                           throws IOException {
    List<String> files = splitFileNames(wordFiles);
	CharArraySet words = null;
    if (files.size() > 0) {
      // default stopwords list has 35 or so words, but maybe don't make it that
      // big to start
      words = new CharArraySet(LUCENE_46, files.size() * 10, ignoreCase);
      for (String file : files) {
        List<String> wlist = getLines(loader, file.trim());
    	words.addAll(StopFilter.makeStopSet(LUCENE_46, wlist, ignoreCase));
      }
    }
    return words;
  }

  private List<String> getLines(ResourceLoader loader, String resource) throws IOException {
	return WordlistLoader.getLines(loader.openResource(resource), Charset.forName("UTF-8"));
  }

  private List<String> splitFileNames(String fileNames) {
    if (fileNames == null)
      return Collections.<String>emptyList();

    List<String> result = new ArrayList<String>();
    for (String file : fileNames.split(NOT_ESCAPED + ",")) {
      result.add(file.replaceAll("\\\\(?=,)", ""));
    }

    return result;
  }


  /**
   * Build a regex matching SOLR special characters that are not wildcards.
   *
   * @return regex matching SOLR special characters that are not wildcards
   */
  private static String escapeNonWildcardRegex(char[] chars) {
    StringBuilder stringBuilder = new StringBuilder("(");

    for (char c : chars) {
      stringBuilder.append('\\').append(c).append('|');
    }

    stringBuilder.setLength(stringBuilder.length() - 1);
    return stringBuilder.append(")").toString();
  }
}