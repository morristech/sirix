package org.sirix.axis.temporal;

import java.util.Iterator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sirix.Holder;
import org.sirix.TestHelper;
import org.sirix.api.XdmNodeReadTrx;
import org.sirix.api.XdmNodeWriteTrx;
import org.sirix.axis.IncludeSelf;
import org.sirix.exception.SirixException;
import org.sirix.utils.DocumentCreator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.testing.IteratorFeature;
import com.google.common.collect.testing.IteratorTester;

/**
 * Test {@link PastAxis}.
 *
 * @author Johannes Lichtenberger
 *
 */
public final class PastAxisTest {

  /** Number of iterations. */
  private static final int ITERATIONS = 5;

  /** The {@link Holder} instance. */
  private Holder holder;

  @Before
  public void setUp() throws SirixException {
    TestHelper.deleteEverything();
    try (final XdmNodeWriteTrx wtx = Holder.generateWtx().getXdmNodeWriteTrx()) {
      DocumentCreator.createVersioned(wtx);
    }
    holder = Holder.generateRtx();
  }

  @After
  public void tearDown() throws SirixException {
    holder.close();
    TestHelper.closeEverything();
  }

  @Test
  public void testPastOrSelfAxis() throws SirixException {
    final XdmNodeReadTrx firstRtx = holder.getResourceManager().beginNodeReadTrx(1);
    final XdmNodeReadTrx secondRtx = holder.getResourceManager().beginNodeReadTrx(2);
    final XdmNodeReadTrx thirdRtx = holder.getXdmNodeReadTrx();

    new IteratorTester<>(ITERATIONS, IteratorFeature.UNMODIFIABLE,
        ImmutableList.of(thirdRtx, secondRtx, firstRtx), null) {
      @Override
      protected Iterator<XdmNodeReadTrx> newTargetIterator() {
        return new PastAxis(thirdRtx, IncludeSelf.YES);
      }
    }.test();
  }

  @Test
  public void testPastAxis() throws SirixException {
    final XdmNodeReadTrx firstRtx = holder.getResourceManager().beginNodeReadTrx(1);
    final XdmNodeReadTrx secondRtx = holder.getResourceManager().beginNodeReadTrx(2);
    final XdmNodeReadTrx thirdRtx = holder.getXdmNodeReadTrx();

    new IteratorTester<XdmNodeReadTrx>(ITERATIONS, IteratorFeature.UNMODIFIABLE,
        ImmutableList.of(secondRtx, firstRtx), null) {
      @Override
      protected Iterator<XdmNodeReadTrx> newTargetIterator() {
        return new PastAxis(thirdRtx);
      }
    }.test();
  }

  @Test
  public void testPastAxisWithRemovedNode() throws SirixException {
    try (final XdmNodeWriteTrx wtx = holder.getResourceManager().beginNodeWriteTrx()) {
      // Revision 4.
      wtx.commit();

      // Revision 5.
      wtx.commit();
    }

    try (final XdmNodeReadTrx thirdReader = holder.getResourceManager().beginNodeReadTrx(3);
        final XdmNodeReadTrx fourthReader = holder.getResourceManager().beginNodeReadTrx(4);
        final XdmNodeReadTrx fifthReader = holder.getResourceManager().beginNodeReadTrx(5)) {
      thirdReader.moveTo(16);
      fourthReader.moveTo(16);
      fifthReader.moveTo(16);

      new IteratorTester<>(ITERATIONS, IteratorFeature.UNMODIFIABLE,
          ImmutableList.of(fifthReader, fourthReader, thirdReader), null) {
        @Override
        protected Iterator<XdmNodeReadTrx> newTargetIterator() {
          return new PastAxis(fifthReader, IncludeSelf.YES);
        }
      }.test();
    }
  }
}
