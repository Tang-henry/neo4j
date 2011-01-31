/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.ha;

import static java.lang.management.ManagementFactory.getPlatformMBeanServer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.zookeeper.server.quorum.QuorumMXBean;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;
import org.jboss.netty.handler.timeout.TimeoutException;
import org.junit.Ignore;
import org.neo4j.test.SubProcess;
import org.neo4j.test.TargetDirectory;

@Ignore
public final class LocalhostZooKeeperCluster
{
    private final ZooKeeper[] keeper;
    private final String connection;

    public LocalhostZooKeeperCluster( Class<?> owningTest, int... ports )
    {
        this( TargetDirectory.forTest( owningTest ), ports );
    }

    public LocalhostZooKeeperCluster( TargetDirectory target, int... ports )
    {
        keeper = new ZooKeeper[ports.length];
        boolean success = false;
        try
        {
            ZooKeeperProcess subprocess = new ZooKeeperProcess( null );
            StringBuilder connection = new StringBuilder();
            for ( int i = 0; i < keeper.length; i++ )
            {
                keeper[i] = subprocess.start( new String[] { config( target, i + 1, ports[i] ) } );
                if ( connection.length() > 0 ) connection.append( "," );
                connection.append( "localhost:" + ports[i] );
            }
            this.connection = connection.toString();
            await( keeper, 10, TimeUnit.SECONDS );
            success = true;
        }
        finally
        {
            if ( !success ) shutdown();
        }
    }

    private static void await( ZooKeeper[] keepers, long timeout, TimeUnit unit )
    {
        timeout = System.currentTimeMillis() + unit.toMillis( timeout );
        boolean done;
        do
        {
            done = true;
            for ( ZooKeeper keeper : keepers )
            {
                if ( keeper.getQuorumSize() != keepers.length ) done = false;
            }
            if ( System.currentTimeMillis() > timeout )
                throw new TimeoutException( "waiting for ZooKeeper cluster to start" );
            try
            {
                Thread.sleep( 10 );
            }
            catch ( InterruptedException e )
            {
                throw new TimeoutException( "waiting for ZooKeeper cluster to start", e );
            }
        }
        while ( !done );
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "[" + getConnectionString() + "]";
    }

    public synchronized String getConnectionString()
    {
        return connection;
    }

    String getStatus()
    {
        StringBuilder result = new StringBuilder();
        String prefix = "";
        for ( ZooKeeper zk : keeper )
        {
            result.append( prefix ).append( zk ).append( ": " ).append( zk.getStatus() );
            prefix = ", ";
        }
        return result.toString();
    }

    private String config( TargetDirectory target, int id, int port )
    {
        File config = target.file( "zookeeper" + id + ".cfg" );
        File dataDir = target.directory( "zk" + id + "data", true );
        try
        {
            PrintWriter conf = new PrintWriter( config );
            try
            {
                conf.println( "tickTime=2000" );
                conf.println( "initLimit=10" );
                conf.println( "syncLimit=5" );
                conf.println( "dataDir=" + dataDir.getAbsolutePath() );
                conf.println( "clientPort=" + port );
                for ( int j = 0; j < keeper.length; j++ )
                {
                    conf.println( "server." + ( j + 1 ) + "=localhost:" + ( 2888 + j ) + ":"
                                  + ( 3888 + j ) );
                }
            }
            finally
            {
                conf.close();
            }
            PrintWriter myid = new PrintWriter( new File( dataDir, "myid" ) );
            try
            {
                myid.println( Integer.toString( id ) );
            }
            finally
            {
                myid.close();
            }
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( "Could not write ZooKeeper configuration", e );
        }
        return config.getAbsolutePath();
    }

    public synchronized void shutdown()
    {
        if ( keeper.length > 0 && keeper[0] == null ) return;
        for ( ZooKeeper zk : keeper )
        {
            if ( zk != null ) SubProcess.stop( zk );
        }
        Arrays.fill( keeper, null );
    }

    public static void main( String[] args ) throws Exception
    {
        LocalhostZooKeeperCluster cluster = new LocalhostZooKeeperCluster( ZooKeeperProcess.class,
                2181, 2182, 2183 );
        try
        {
            System.out.println( "press return to exit" );
            System.in.read();
        }
        finally
        {
            cluster.shutdown();
        }
    }

    public interface ZooKeeper
    {
        int getQuorumSize();

        String getStatus();
    }

    private static class ZooKeeperProcess extends SubProcess<ZooKeeper, String[]> implements
            ZooKeeper
    {
        private final String name;

        ZooKeeperProcess( String name )
        {
            this.name = name;
        }

        @Override
        protected void startup( String[] parameters )
        {
            System.out.println( "parameters=" + Arrays.toString( parameters ) );
            QuorumPeerMain.main( parameters );
        }

        @Override
        public String toString()
        {
            if ( name != null )
            {
                return super.toString() + ":" + name;
            }
            else
            {
                return super.toString();
            }
        }

        public int getQuorumSize()
        {
            try
            {
                return quorumBean().getQuorumSize();
            }
            catch ( Exception e )
            {
                return 0;
            }
        }

        public String getStatus()
        {
            try
            {
                return status( quorumBean() );
            }
            catch ( Exception e )
            {
                return "-down-";
            }
        }

        private QuorumMXBean quorumBean() throws MalformedObjectNameException
        {
            Set<ObjectName> names = getPlatformMBeanServer().queryNames(
                    new ObjectName( "org.apache.ZooKeeperService:name0=ReplicatedServer_id*" ),
                    null );
            QuorumMXBean quorum = MBeanServerInvocationHandler.newProxyInstance(
                    getPlatformMBeanServer(), names.iterator().next(), QuorumMXBean.class, false );
            return quorum;
        }

        @SuppressWarnings( "boxing" )
        private String status( QuorumMXBean quorumBean )
        {
            return String.format( "name=%s, size=%s", quorumBean.getName(),
                    quorumBean.getQuorumSize() );
        }
    }
}
