package net.contrapunctus.lzma;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.CRC32;
import org.junit.Test;
import org.junit.Assert;

/**
 * Some tests to exercise the ConcurrentBufferOutputStream. One test
 * is randomized, but the seed is printed, so that results should be
 * reproducible if needed.
 */
public class ConcurrentBufferOutputTest
{
    @Test public void withRandomSeed() throws Exception
    {
        long seed = System.currentTimeMillis();
        System.out.println("seed " + seed);
        withSeed(seed);
    }

    static final int MAX_BUFFER = ConcurrentBufferOutputStream.BUFSIZE * 2;
    static final int MAX_ITERS = ConcurrentBufferOutputStream.QUEUESIZE * 2;
    private static final boolean DEBUG;

    static {
        String ds = null;
        try { ds = System.getProperty("DEBUG_ConcurrentTest"); }
        catch(SecurityException e) { }
        DEBUG = ds != null;
    }

    abstract class Summer extends Thread
    {
        protected CRC32 sum = new CRC32();
        Exception exn;

        long getSum()
        {
            return sum.getValue();
        }

        protected abstract void checkedRun()
            throws IOException, InterruptedException;

        public void run()
        {
            try
                {
                    checkedRun();
                }
            catch(Exception exn)
                {
                    this.exn = exn;
                }
        }
    }

    abstract class Writer extends Summer
    {
        protected OutputStream os;

        Writer init(OutputStream os)
        {
            this.os = os;
            return this;
        }

        void write(int i) throws IOException
        {
            os.write(i);
            sum.update(i);
            if(DEBUG) System.out.println("wrote 1 byte");
        }

        void write(byte[] buf) throws IOException
        {
            os.write(buf);
            sum.update(buf, 0, buf.length);
            if(DEBUG) System.out.println("wrote "+ buf.length+ " bytes");
        }

        void write(byte[] buf, int off, int len) throws IOException
        {
            os.write(buf, off, len);
            sum.update(buf, off, len);
            if(DEBUG) System.out.println("wrote "+ len+ " bytes at "+ off);
        }
    }

    class BoundaryWriter extends Writer
    {
        protected void checkedRun() throws IOException
        {
            // write all the byte values (incl overflowed ones) as ints
            for(int i = -255; i <= 255; i++)
                {
                    write(i);
                }
            // attempt to write a sentinel
            write(new byte[0]);
            write(new byte[0], 0, 0);
            // one more byte, then close
            write(42);
            os.close();
        }
    }

    class RandomWriter extends Writer
    {
        protected Random rng;

        RandomWriter(Random rng)
        {
            this.rng = rng;
        }

        void write() throws IOException
        {
            byte[] bs = new byte[rng.nextInt(MAX_BUFFER)+1];
            rng.nextBytes(bs);
            switch(rng.nextInt(4))
                {
                case 0:         // write single byte
                    write(bs[0]);
                    break;
                case 1:         // write slice of array
                    int off = rng.nextInt(bs.length-1);
                    int len = rng.nextInt(bs.length-off-1)+1;
                    write(bs, off, len);
                    break;
                default:
                    write(bs);
                }
        }

        protected void checkedRun() throws IOException
        {
            for(int i = rng.nextInt(MAX_ITERS) + 5; i >= 0; i--)
                {
                    if(rng.nextBoolean()) yield();
                    write();
                }
            os.close();
        }
    }

    class Reader extends Summer
    {
        protected ArrayBlockingQueue<byte[]> q;

        Reader(ArrayBlockingQueue<byte[]> q)
        {
            this.q = q;
        }

        protected void checkedRun() throws InterruptedException
        {
            byte[] bs = q.take();
            while(bs.length > 0)
                {
                    if(DEBUG) System.out.println("read "+ bs.length+ " bytes");
                    sum.update(bs, 0, bs.length);
                    if(bs.length%11==0) Thread.yield();
                    bs = q.take();
                }
        }
    }

    private void testReadWrite(Writer wr) throws InterruptedException
    {
        ArrayBlockingQueue<byte[]> q =
            ConcurrentBufferOutputStream.newQueue();
        OutputStream os = ConcurrentBufferOutputStream.create(q);
        wr.init(os);
        wr.start();
        Reader rd = new Reader(q);
        rd.run();
        wr.join();
        Assert.assertNull(wr.exn);
        Assert.assertNull(rd.exn);
        System.out.printf("sums %x -> %x\n", wr.getSum(), rd.getSum());
        Assert.assertEquals(wr.getSum(), rd.getSum());
    }

    private void withSeed(long seed) throws InterruptedException
    {
        testReadWrite(new RandomWriter(new Random(seed)));
    }

    @Test public void boundaryTest() throws InterruptedException
    {
        testReadWrite(new BoundaryWriter());
    }
}
