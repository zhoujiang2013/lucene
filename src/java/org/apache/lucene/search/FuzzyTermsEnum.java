package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.BasicAutomata;
import org.apache.lucene.util.automaton.BasicOperations;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.RunAutomaton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Subclass of TermsEnum for enumerating all terms that are similar
 * to the specified filter term.
 *
 * <p>Term enumerations are always ordered by
 * {@link #getTermComparator}.  Each term in the enumeration is
 * greater than all that precede it.</p>
 */
public final class FuzzyTermsEnum extends TermsEnum {
  private TermsEnum actualEnum;
  private MultiTermQuery.BoostAttribute actualBoostAtt;
  
  private final MultiTermQuery.BoostAttribute boostAtt =
    attributes().addAttribute(MultiTermQuery.BoostAttribute.class);

  private float bottom = boostAtt.getMaxNonCompetitiveBoost();
  
  private final float minSimilarity;
  private final float scale_factor;
  
  private final int termLength;
  
  private int maxEdits;
  private List<Automaton> automata;
  private List<RunAutomaton> runAutomata;
  
  private final IndexReader reader;
  private final Term term;
  private final int realPrefixLength;
  
  /**
   * Constructor for enumeration of all terms from specified <code>reader</code> which share a prefix of
   * length <code>prefixLength</code> with <code>term</code> and which have a fuzzy similarity &gt;
   * <code>minSimilarity</code>.
   * <p>
   * After calling the constructor the enumeration is already pointing to the first 
   * valid term if such a term exists. 
   * 
   * @param reader Delivers terms.
   * @param term Pattern term.
   * @param minSimilarity Minimum required similarity for terms from the reader. Default value is 0.5f.
   * @param prefixLength Length of required common prefix. Default value is 0.
   * @throws IOException
   */
  public FuzzyTermsEnum(IndexReader reader, Term term, 
      final float minSimilarity, final int prefixLength) throws IOException {
    if (minSimilarity >= 1.0f)
      throw new IllegalArgumentException("minimumSimilarity cannot be greater than or equal to 1");
    else if (minSimilarity < 0.0f)
      throw new IllegalArgumentException("minimumSimilarity cannot be less than 0");
    if(prefixLength < 0)
      throw new IllegalArgumentException("prefixLength cannot be less than 0");
    this.reader = reader;
    this.term = term;
    //The prefix could be longer than the word.
    //It's kind of silly though.  It means we must match the entire word.
    this.termLength = term.text().length();
    this.realPrefixLength = prefixLength > termLength ? termLength : prefixLength;
    this.minSimilarity = minSimilarity;
    this.scale_factor = 1.0f / (1.0f - minSimilarity);
    
    // calculate the maximum k edits for this similarity
    maxEdits = initialMaxDistance(minSimilarity, termLength);
  
    TermsEnum subEnum = getAutomatonEnum(maxEdits, null);
    setEnum(subEnum != null ? subEnum : 
      new LinearFuzzyTermsEnum(reader, term, minSimilarity, prefixLength));
  }
  
  /**
   * return an automata-based enum for matching up to editDistance from
   * lastTerm, if possible
   */
  private TermsEnum getAutomatonEnum(int editDistance, BytesRef lastTerm)
      throws IOException {
    initAutomata(editDistance);
    if (automata != null && editDistance < automata.size()) {
      return new AutomatonFuzzyTermsEnum(automata.get(editDistance), term,
          reader, minSimilarity, runAutomata.subList(0, editDistance + 1)
              .toArray(new RunAutomaton[0]), lastTerm);
    } else {
      return null;
    }
  }
  
  /** initialize levenshtein DFAs up to maxDistance, if possible */
  private void initAutomata(int maxDistance) {
    if (automata == null && 
        maxDistance <= LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE) {
      LevenshteinAutomata builder = 
        new LevenshteinAutomata(term.text().substring(realPrefixLength));
      automata = new ArrayList<Automaton>(maxDistance);
      runAutomata = new ArrayList<RunAutomaton>(maxDistance);
      for (int i = 0; i <= maxDistance; i++) {
        Automaton a = builder.toAutomaton(i);
        // constant prefix
        if (realPrefixLength > 0) {
          Automaton prefix = BasicAutomata.makeString(
              term.text().substring(0, realPrefixLength));
          a = BasicOperations.concatenate(prefix, a);
        }
        automata.add(a);
        runAutomata.add(new RunAutomaton(a));
      }
    }
  }
 
  /** swap in a new actual enum to proxy to */
  private void setEnum(TermsEnum actualEnum) {
    this.actualEnum = actualEnum;
    this.actualBoostAtt = actualEnum.attributes().addAttribute(
        MultiTermQuery.BoostAttribute.class);
  }
  
  /**
   * fired when the max non-competitive boost has changed. this is the hook to
   * swap in a smarter actualEnum
   */
  private void bottomChanged(float boostValue, BytesRef lastTerm)
      throws IOException {
    int oldMaxEdits = maxEdits;
    
    // as long as the max non-competitive boost is >= the max boost
    // for some edit distance, keep dropping the max edit distance.
    while (maxEdits > 0 && boostValue >= calculateMaxBoost(maxEdits))
      maxEdits--;
    
    if (oldMaxEdits != maxEdits) { // the maximum n has changed
      TermsEnum newEnum = getAutomatonEnum(maxEdits, lastTerm);
      if (newEnum != null) {
        setEnum(newEnum);
      }
    }
    // TODO, besides changing linear -> automaton, and swapping in a smaller
    // automaton, we can also use this information to optimize the linear case
    // itself: re-init maxDistances so the fast-fail happens for more terms due
    // to the now stricter constraints.
  }
   
  // for some raw min similarity and input term length, the maximum # of edits
  private int initialMaxDistance(float minimumSimilarity, int termLen) {
    return (int) ((1-minimumSimilarity) * termLen);
  }
  
  // for some number of edits, the maximum possible scaled boost
  private float calculateMaxBoost(int nEdits) {
    final float similarity = 1.0f - ((float) nEdits / (float) (termLength));
    return (similarity - minSimilarity) * scale_factor;
  }

  @Override
  public BytesRef next() throws IOException {
    BytesRef term = actualEnum.next();
    boostAtt.setBoost(actualBoostAtt.getBoost());
    
    final float bottom = boostAtt.getMaxNonCompetitiveBoost();
    if (bottom != this.bottom) {
      this.bottom = bottom;
      // clone the term before potentially doing something with it
      // this is a rare but wonderful occurrence anyway
      bottomChanged(bottom, term == null ? null : (BytesRef) term.clone());
    }
    
    return term;
  }
  
  // proxy all other enum calls to the actual enum
  @Override
  public int docFreq() {
    return actualEnum.docFreq();
  }
  
  @Override
  public DocsEnum docs(Bits skipDocs, DocsEnum reuse) throws IOException {
    return actualEnum.docs(skipDocs, reuse);
  }
  
  @Override
  public DocsAndPositionsEnum docsAndPositions(Bits skipDocs,
      DocsAndPositionsEnum reuse) throws IOException {
    return actualEnum.docsAndPositions(skipDocs, reuse);
  }
  
  @Override
  public Comparator<BytesRef> getComparator() throws IOException {
    return actualEnum.getComparator();
  }
  
  @Override
  public long ord() throws IOException {
    return actualEnum.ord();
  }
  
  @Override
  public SeekStatus seek(BytesRef text) throws IOException {
    return actualEnum.seek(text);
  }
  
  @Override
  public SeekStatus seek(long ord) throws IOException {
    return actualEnum.seek(ord);
  }
  
  @Override
  public BytesRef term() throws IOException {
    return actualEnum.term();
  }
}

/**
 * Implement fuzzy enumeration with automaton.
 * <p>
 * This is the fastest method as opposed to LinearFuzzyTermsEnum:
 * as enumeration is logarithmic to the number of terms (instead of linear)
 * and comparison is linear to length of the term (rather than quadratic)
 */
final class AutomatonFuzzyTermsEnum extends AutomatonTermsEnum {
  private final RunAutomaton matchers[];
  // used for unicode conversion from BytesRef byte[] to char[]
  private final UnicodeUtil.UTF16Result utf16 = new UnicodeUtil.UTF16Result();
  
  private final float minimumSimilarity;
  private final float scale_factor;
  
  private final int fullSearchTermLength;
  private final BytesRef termRef;
  
  private final BytesRef lastTerm;
  private final MultiTermQuery.BoostAttribute boostAtt =
    attributes().addAttribute(MultiTermQuery.BoostAttribute.class);
  
  public AutomatonFuzzyTermsEnum(Automaton automaton, Term queryTerm,
      IndexReader reader, float minSimilarity, RunAutomaton matchers[], BytesRef lastTerm) throws IOException {
    super(automaton, matchers[matchers.length - 1], queryTerm, reader, false);
    this.minimumSimilarity = minSimilarity;
    this.scale_factor = 1.0f / (1.0f - minimumSimilarity);
    this.matchers = matchers;
    this.lastTerm = lastTerm;
    termRef = new BytesRef(queryTerm.text());
    fullSearchTermLength = queryTerm.text().length();
  }
  
  /** finds the smallest Lev(n) DFA that accepts the term. */
  @Override
  protected AcceptStatus accept(BytesRef term) {
    if (term.equals(termRef)) { // ed = 0
      boostAtt.setBoost(1.0F);
      return AcceptStatus.YES_AND_SEEK;
    }
    
    UnicodeUtil.UTF8toUTF16(term.bytes, term.offset, term.length, utf16);
    
    // TODO: benchmark doing this backwards
    for (int i = 1; i < matchers.length; i++)
      if (matchers[i].run(utf16.result, 0, utf16.length)) {
        final float similarity = 1.0f - ((float) i / (float) 
            (Math.min(utf16.length, fullSearchTermLength)));
        if (similarity > minimumSimilarity) {
          boostAtt.setBoost((float) ((similarity - minimumSimilarity) * scale_factor));
          return AcceptStatus.YES_AND_SEEK;
        } else {
          return AcceptStatus.NO_AND_SEEK;
        }
      }

    return AcceptStatus.NO_AND_SEEK;
  }

  /** defers to superclass, except can start at an arbitrary location */
  @Override
  protected BytesRef nextSeekTerm(BytesRef term) throws IOException {
    if (term == null)
      term = lastTerm;
    return super.nextSeekTerm(term);
  }
}

/**
 * Implement fuzzy enumeration with linear brute force.
 */
final class LinearFuzzyTermsEnum extends FilteredTermsEnum {

  /* This should be somewhere around the average long word.
   * If it is longer, we waste time and space. If it is shorter, we waste a
   * little bit of time growing the array as we encounter longer words.
   */
  private static final int TYPICAL_LONGEST_WORD_IN_INDEX = 19;

  /* Allows us save time required to create a new array
   * every time similarity is called.
   */
  private int[][] d;

  private final char[] text;
  private final int prefixLen;

  private final float minimumSimilarity;
  private final float scale_factor;
  private final int[] maxDistances = new int[TYPICAL_LONGEST_WORD_IN_INDEX];
  
  private final MultiTermQuery.BoostAttribute boostAtt =
    attributes().addAttribute(MultiTermQuery.BoostAttribute.class);
   
  /**
   * Constructor for enumeration of all terms from specified <code>reader</code> which share a prefix of
   * length <code>prefixLength</code> with <code>term</code> and which have a fuzzy similarity &gt;
   * <code>minSimilarity</code>.
   * <p>
   * After calling the constructor the enumeration is already pointing to the first 
   * valid term if such a term exists. 
   * 
   * @param reader Delivers terms.
   * @param term Pattern term.
   * @param minSimilarity Minimum required similarity for terms from the reader. Default value is 0.5f.
   * @param prefixLength Length of required common prefix. Default value is 0.
   * @throws IOException
   */
  public LinearFuzzyTermsEnum(IndexReader reader, Term term, final float minSimilarity, final int prefixLength) throws IOException {
    super(reader, term.field());
    
    if (minSimilarity >= 1.0f)
      throw new IllegalArgumentException("minimumSimilarity cannot be greater than or equal to 1");
    else if (minSimilarity < 0.0f)
      throw new IllegalArgumentException("minimumSimilarity cannot be less than 0");
    if(prefixLength < 0)
      throw new IllegalArgumentException("prefixLength cannot be less than 0");

    this.minimumSimilarity = minSimilarity;
    this.scale_factor = 1.0f / (1.0f - minimumSimilarity);

    //The prefix could be longer than the word.
    //It's kind of silly though.  It means we must match the entire word.
    final int fullSearchTermLength = term.text().length();
    final int realPrefixLength = prefixLength > fullSearchTermLength ? fullSearchTermLength : prefixLength;

    this.text = term.text().substring(realPrefixLength).toCharArray();
    final String prefix = term.text().substring(0, realPrefixLength);
    prefixBytesRef = new BytesRef(prefix);
    prefixLen = prefix.length();
    initializeMaxDistances();
    this.d = initDistanceArray();

    setInitialSeekTerm(prefixBytesRef);
  }

  private final BytesRef prefixBytesRef;
  // used for unicode conversion from BytesRef byte[] to char[]
  private final UnicodeUtil.UTF16Result utf16 = new UnicodeUtil.UTF16Result();
  
  /**
   * The termCompare method in FuzzyTermEnum uses Levenshtein distance to 
   * calculate the distance between the given term and the comparing term. 
   */
  @Override
  protected final AcceptStatus accept(BytesRef term) {
    if (term.startsWith(prefixBytesRef)) {
      UnicodeUtil.UTF8toUTF16(term.bytes, term.offset, term.length, utf16);
      final float similarity = similarity(utf16.result, prefixLen, utf16.length - prefixLen);
      if (similarity > minimumSimilarity) {
        boostAtt.setBoost((float)((similarity - minimumSimilarity) * scale_factor));
        return AcceptStatus.YES;
      } else return AcceptStatus.NO;
    } else {
      return AcceptStatus.END;
    }
  }
  
  /******************************
   * Compute Levenshtein distance
   ******************************/
  
  /**
   * Finds and returns the smallest of three integers 
   */
  private static final int min(int a, int b, int c) {
    final int t = (a < b) ? a : b;
    return (t < c) ? t : c;
  }

  private final int[][] initDistanceArray(){
    return new int[this.text.length + 1][TYPICAL_LONGEST_WORD_IN_INDEX];
  }

  /**
   * <p>Similarity returns a number that is 1.0f or less (including negative numbers)
   * based on how similar the Term is compared to a target term.  It returns
   * exactly 0.0f when
   * <pre>
   *    editDistance &gt; maximumEditDistance</pre>
   * Otherwise it returns:
   * <pre>
   *    1 - (editDistance / length)</pre>
   * where length is the length of the shortest term (text or target) including a
   * prefix that are identical and editDistance is the Levenshtein distance for
   * the two words.</p>
   *
   * <p>Embedded within this algorithm is a fail-fast Levenshtein distance
   * algorithm.  The fail-fast algorithm differs from the standard Levenshtein
   * distance algorithm in that it is aborted if it is discovered that the
   * minimum distance between the words is greater than some threshold.
   *
   * <p>To calculate the maximum distance threshold we use the following formula:
   * <pre>
   *     (1 - minimumSimilarity) * length</pre>
   * where length is the shortest term including any prefix that is not part of the
   * similarity comparison.  This formula was derived by solving for what maximum value
   * of distance returns false for the following statements:
   * <pre>
   *   similarity = 1 - ((float)distance / (float) (prefixLength + Math.min(textlen, targetlen)));
   *   return (similarity > minimumSimilarity);</pre>
   * where distance is the Levenshtein distance for the two words.
   * </p>
   * <p>Levenshtein distance (also known as edit distance) is a measure of similarity
   * between two strings where the distance is measured as the number of character
   * deletions, insertions or substitutions required to transform one string to
   * the other string.
   * @param target the target word or phrase
   * @return the similarity,  0.0 or less indicates that it matches less than the required
   * threshold and 1.0 indicates that the text and target are identical
   */
  private final float similarity(final char[] target, int offset, int length) {
    final int m = length;
    final int n = text.length;
    if (n == 0)  {
      //we don't have anything to compare.  That means if we just add
      //the letters for m we get the new word
      return prefixLen == 0 ? 0.0f : 1.0f - ((float) m / prefixLen);
    }
    if (m == 0) {
      return prefixLen == 0 ? 0.0f : 1.0f - ((float) n / prefixLen);
    }

    final int maxDistance = getMaxDistance(m);

    if (maxDistance < Math.abs(m-n)) {
      //just adding the characters of m to n or vice-versa results in
      //too many edits
      //for example "pre" length is 3 and "prefixes" length is 8.  We can see that
      //given this optimal circumstance, the edit distance cannot be less than 5.
      //which is 8-3 or more precisely Math.abs(3-8).
      //if our maximum edit distance is 4, then we can discard this word
      //without looking at it.
      return 0.0f;
    }

    //let's make sure we have enough room in our array to do the distance calculations.
    if (d[0].length <= m) {
      growDistanceArray(m);
    }

    // init matrix d
    for (int i = 0; i <= n; i++) d[i][0] = i;
    for (int j = 0; j <= m; j++) d[0][j] = j;
    
    // start computing edit distance
    for (int i = 1; i <= n; i++) {
      int bestPossibleEditDistance = m;
      final char s_i = text[i - 1];
      for (int j = 1; j <= m; j++) {
        if (s_i != target[offset+j-1]) {
            d[i][j] = min(d[i-1][j], d[i][j-1], d[i-1][j-1])+1;
        }
        else {
          d[i][j] = min(d[i-1][j]+1, d[i][j-1]+1, d[i-1][j-1]);
        }
        bestPossibleEditDistance = Math.min(bestPossibleEditDistance, d[i][j]);
      }

      //After calculating row i, the best possible edit distance
      //can be found by found by finding the smallest value in a given column.
      //If the bestPossibleEditDistance is greater than the max distance, abort.

      if (i > maxDistance && bestPossibleEditDistance > maxDistance) {  //equal is okay, but not greater
        //the closest the target can be to the text is just too far away.
        //this target is leaving the party early.
        return 0.0f;
      }
    }

    // this will return less than 0.0 when the edit distance is
    // greater than the number of characters in the shorter word.
    // but this was the formula that was previously used in FuzzyTermEnum,
    // so it has not been changed (even though minimumSimilarity must be
    // greater than 0.0)
    return 1.0f - ((float)d[n][m] / (float) (prefixLen + Math.min(n, m)));
  }

  /**
   * Grow the second dimension of the array, so that we can calculate the
   * Levenshtein difference.
   */
  private void growDistanceArray(int m) {
    for (int i = 0; i < d.length; i++) {
      d[i] = new int[m+1];
    }
  }

  /**
   * The max Distance is the maximum Levenshtein distance for the text
   * compared to some other value that results in score that is
   * better than the minimum similarity.
   * @param m the length of the "other value"
   * @return the maximum levenshtein distance that we care about
   */
  private final int getMaxDistance(int m) {
    return (m < maxDistances.length) ? maxDistances[m] : calculateMaxDistance(m);
  }

  private void initializeMaxDistances() {
    for (int i = 0; i < maxDistances.length; i++) {
      maxDistances[i] = calculateMaxDistance(i);
    }
  }
  
  private int calculateMaxDistance(int m) {
    return (int) ((1-minimumSimilarity) * (Math.min(text.length, m) + prefixLen));
  }
}