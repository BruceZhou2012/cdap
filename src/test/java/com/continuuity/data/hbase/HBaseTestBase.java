package com.continuuity.data.hbase;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * A base class that can be used to easily run a test within an embedded
 * HBase cluster (includes embedded ZooKeeper, HDFS, and HBase).
 *
 * To use, simply extend this class and create your tests like normal.  From
 * within your tests, you can access the underlying HBase cluster through
 * {@link #getConfiguration()}, {@link #getHBaseAdmin()}, and
 * {@link #getHTable(byte[])}.
 *
 * Alternatively, you can call the {@link #startHBase()} and {@link #stopHBase()}
 * methods directly from within your own BeforeClass/AfterClass methods.
 *
 * Note:  This test is somewhat heavy-weight and takes 10-20 seconds to startup.
 */
public abstract class HBaseTestBase {

  protected static Configuration conf;
  
  protected static MiniZooKeeperCluster zkCluster;
  
  protected static MiniDFSCluster dfsCluster;
  
  protected static MiniHBaseCluster hbaseCluster;
  
  // Accessors for test implementations
  
  public static Configuration getConfiguration() {
    return conf;
  }
  
  public static HBaseAdmin getHBaseAdmin() throws IOException {
    return new HBaseAdmin(conf);
  }
  
  public static HTable getHTable(byte [] tableName) throws IOException {
    return new HTable(conf, tableName);
  }
  
  // Temporary directories
  
  private static final Random r = new Random();
  
  public static File getRandomTempDir() {
    File file = new File(System.getProperty("java.io.tmpdir"),
        Integer.toString(Math.abs(r.nextInt())));
    if (!file.mkdir()) {
      throw new RuntimeException("Unable to create temp directory");
    }
    return file;
  }
  
  // Test startup / teardown
  
  @BeforeClass
  public static void startHBase() throws Exception {
    conf = new Configuration();
    
    // Start ZooKeeper
    
    zkCluster = new MiniZooKeeperCluster(conf);
    System.err.println("Starting ZK in 1 sec...");
    Thread.sleep(1000);
    int zkPort = zkCluster.startup(getRandomTempDir(), 1);
    System.err.println("\n\nStarted ZK on port " + zkPort + "\n\n\n");
    
    // Add ZK info to conf
    conf.set(HConstants.ZOOKEEPER_CLIENT_PORT, Integer.toString(zkPort));
    
    // Start DFS
    
    File dfsPath = getRandomTempDir();
    System.setProperty("test.build.data", dfsPath.toString());
    System.err.println("Instantiating dfs cluster in 1 sec...");
    Thread.sleep(1000);
    dfsCluster = new MiniDFSCluster(0, conf, 1,
        true, true, true, null, null, null, null);
    System.err.println("Waiting for dfs to start...");
    dfsCluster.waitClusterUp();
    System.err.println("DFS started...");
    Thread.sleep(1000);
    
    // Add HDFS info to conf
    conf.set("fs.defaultFS", dfsCluster.getFileSystem().getUri().toString());
    
    // Start HBase
    createHBaseRootDir(conf);
    conf.setInt("hbase.master.wait.on.regionservers.mintostart", 1);
    conf.setInt("hbase.master.wait.on.regionservers.maxtostart", 1);
    Configuration c = new Configuration(conf);
    System.err.println("Instantiating HBase cluster in 1 sec...");
    Thread.sleep(1000);
    hbaseCluster = new MiniHBaseCluster(c, 1, 1);
    System.err.println("Just waiting around for a bit now");
    Thread.sleep(1000);
  }
  
  @AfterClass
  public static void stopHBase() throws Exception {
    
    // Stop HBase
    
    System.err.println("\n\n\nShutting down HBase in 1 sec...\n\n\n");
    Thread.sleep(1000);
    hbaseCluster.shutdown();
    System.err.println("\n\n\nDone with HBase shutdown\n\n\n");
    hbaseCluster = null;
    
    // Stop DFS
    
    System.err.println("\n\n\nShutting down DFS in 1 sec...\n\n\n");
    Thread.sleep(1000);
    dfsCluster.shutdown();
    System.err.println("\n\n\nDone with DFS shutdown\n\n\n");
    dfsCluster = null;
    
    // Stop ZK
    
    System.err.println("\n\n\nShutting down ZK in 1 sec...\n\n\n");
    Thread.sleep(1000);
    zkCluster.shutdown();
    System.err.println("\n\n\nDone with zk shutdown\n\n\n");
    zkCluster = null;
  }
  
  // Startup/shutdown helpers
  

  public static Path createHBaseRootDir(Configuration conf) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    Path hbaseRootdir = new Path(
        fs.makeQualified(fs.getHomeDirectory()), "hbase");
    conf.set(HConstants.HBASE_DIR, hbaseRootdir.toString());
    fs.mkdirs(hbaseRootdir);
    FSUtils.setVersion(fs, hbaseRootdir);
    return hbaseRootdir;
  }

  // HRegion-level testing

  public static HRegion createHRegion(byte[] tableName, byte[] startKey,
      byte[] stopKey, String callingMethod, Configuration conf,
      byte[]... families)
      throws IOException {
    if (conf == null) conf = new Configuration();
    HTableDescriptor htd = new HTableDescriptor(tableName);
    for(byte [] family : families) {
      htd.addFamily(new HColumnDescriptor(family));
    }
    HRegionInfo info = new HRegionInfo(htd.getName(), startKey, stopKey, false);
    Path path = new Path(conf.get(HConstants.HBASE_DIR), callingMethod);
    FileSystem fs = FileSystem.get(conf);
    if (fs.exists(path)) {
      if (!fs.delete(path, true)) {
        throw new IOException("Failed delete of " + path);
      }
    }
    return HRegion.createHRegion(info, path, conf, htd);
  }
}
