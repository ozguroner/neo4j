/*
 * Copyright (c) 2002-2010 "Neo Technology,"
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

package org.neo4j.examples.socnet;

import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.helpers.collection.IterableWrapper;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.kernel.Traversal;
import org.neo4j.kernel.Uniqueness;

import java.util.*;

import static org.neo4j.examples.socnet.RelTypes.STATUS;
import static org.neo4j.examples.socnet.RelTypes.FRIEND;
import static org.neo4j.examples.socnet.RelTypes.NEXT;

public class Person
{
    static final String NAME = "person_name";

    // START SNIPPET: the-node
    private final Node underlyingNode;

    Person( Node personNode )
    {
        this.underlyingNode = personNode;
    }

    protected Node getUnderlyingNode()
    {
        return underlyingNode;
    }

    // END SNIPPET: the-node

    // START SNIPPET: delegate-to-the-node
    public String getName()
    {
        return (String)underlyingNode.getProperty( NAME );
    }

    // END SNIPPET: delegate-to-the-node

    // START SNIPPET: override
    @Override
    public int hashCode()
    {
        return underlyingNode.hashCode();
    }

    @Override
    public boolean equals( Object o )
    {
        return o instanceof Person &&
                underlyingNode.equals( ( (Person)o ).getUnderlyingNode() );
    }

    @Override
    public String toString()
    {
        return "Person[" + getName() + "]";
    }

    // END SNIPPET: override

    public void addFriend( Person otherPerson )
    {
        Transaction tx = underlyingNode.getGraphDatabase().beginTx();
        try
        {
            if ( !this.equals( otherPerson ) )
            {
                Relationship friendRel = getFriendRelationshipTo( otherPerson );
                if ( friendRel == null )
                {
                    underlyingNode.createRelationshipTo( otherPerson.getUnderlyingNode(), FRIEND );
                }
                tx.success();
            }
        }
        finally
        {
            tx.finish();
        }
    }

    public int getNrOfFriends()
    {
        return IteratorUtil.count( getFriends() );
    }

    public Iterable<Person> getFriends()
    {
        return getFriendsByDepth( 1 );
    }

    public void removeFriend( Person otherPerson )
    {
        Transaction tx = underlyingNode.getGraphDatabase().beginTx();
        try
        {
            if ( !this.equals( otherPerson ) )
            {
                Relationship friendRel = getFriendRelationshipTo( otherPerson );
                if ( friendRel != null )
                {
                    friendRel.delete();
                }
                tx.success();
            }
        }
        finally
        {
            tx.finish();
        }
    }

    public Iterable<Person> getFriendsOfFriends()
    {
        return getFriendsByDepth( 2 );
    }

    public Iterable<Person> getShortestPathTo( Person otherPerson,
                                               int maxDepth )
    {
        // use graph algo to calculate a shortest path
        PathFinder<Path> finder = GraphAlgoFactory.shortestPath(
                Traversal.expanderForTypes( FRIEND, Direction.BOTH ), maxDepth );

        Path path = finder.findSinglePath( underlyingNode,
                otherPerson.getUnderlyingNode() );
        return createPersonsFromNodes( path );
    }

    public Iterable<Person> getFriendRecommendation(
            int numberOfFriendsToReturn )
    {
        HashSet<Person> friends = new HashSet<Person>();
        IteratorUtil.addToCollection( getFriends(), friends );

        HashSet<Person> friendsOfFriends = new HashSet<Person>();
        IteratorUtil.addToCollection( getFriendsOfFriends(), friendsOfFriends );

        friendsOfFriends.removeAll( friends );

        ArrayList<RankedPerson> rankedFriends = new ArrayList<RankedPerson>();
        for ( Person friend : friendsOfFriends )
        {
            int rank = getPathsToPerson( friend );
            rankedFriends.add( new RankedPerson( friend, rank ) );
        }

        Collections.sort( rankedFriends, new RankedComparer() );
        trimTo( rankedFriends, numberOfFriendsToReturn );

        return onlyFriend( rankedFriends );
    }

    public Iterable<StatusUpdate> getStatus()
    {
        Relationship firstStatus = underlyingNode.getSingleRelationship(
                STATUS, Direction.OUTGOING );
        if ( firstStatus == null )
        {
            return Collections.emptyList();
        }

        TraversalDescription traversal = Traversal.description().
                depthFirst().
                relationships( NEXT ).
                filter( Traversal.returnAll() );

        return new IterableWrapper<StatusUpdate, Path>(
                traversal.traverse( firstStatus.getEndNode() ) )
        {
            @Override
            protected StatusUpdate underlyingObjectToObject( Path path )
            {
                return new StatusUpdate( path.endNode() );
            }
        };
    }

    public Iterator<StatusUpdate> friendStatuses()
    {
        return new FriendsStatusUpdateIterator( this );
    }

    public void addStatus( String text )
    {
        Transaction tx = graphDb().beginTx();
        try
        {
            StatusUpdate oldStatus;
            if ( getStatus().iterator().hasNext() )
            {
                oldStatus = getStatus().iterator().next();
            } else
            {
                oldStatus = null;
            }

            Node newStatus = createNewStatusNode( text );

            if ( oldStatus != null )
            {
                underlyingNode.getSingleRelationship( RelTypes.STATUS, Direction.OUTGOING ).delete();
                newStatus.createRelationshipTo( oldStatus.getUnderlyingNode(), RelTypes.NEXT );
            }

            underlyingNode.createRelationshipTo( newStatus, RelTypes.STATUS );
            tx.success();
        }
        finally
        {
            tx.finish();
        }
    }

    private GraphDatabaseService graphDb()
    {
        return underlyingNode.getGraphDatabase();
    }

    private Node createNewStatusNode( String text )
    {
        Node newStatus = graphDb().createNode();
        newStatus.setProperty( StatusUpdate.TEXT, text );
        newStatus.setProperty( StatusUpdate.DATE, new Date().getTime() );
        return newStatus;
    }

    private final class RankedPerson
    {
        final Person person;

        final int rank;

        private RankedPerson( Person person, int rank )
        {

            this.person = person;
            this.rank = rank;
        }

        public Person getPerson()
        {
            return person;
        }
        public int getRank()
        {
            return rank;
        }

    }

    private class RankedComparer implements Comparator<RankedPerson>
    {
        public int compare( RankedPerson a, RankedPerson b )
        {
            return b.getRank() - a.getRank();
        }

    }

    private void trimTo( ArrayList<RankedPerson> rankedFriends,
                         int numberOfFriendsToReturn )
    {
        while ( rankedFriends.size() > numberOfFriendsToReturn )
        {
            rankedFriends.remove( rankedFriends.size() - 1 );
        }
    }

    private Iterable<Person> onlyFriend( Iterable<RankedPerson> rankedFriends )
    {
        ArrayList<Person> retVal = new ArrayList<Person>();
        for ( RankedPerson person : rankedFriends )
        {
            retVal.add( person.getPerson() );
        }
        return retVal;
    }

    private Relationship getFriendRelationshipTo( Person otherPerson )
    {
        Node otherNode = otherPerson.getUnderlyingNode();
        for ( Relationship rel : underlyingNode.getRelationships( FRIEND ) )
        {
            if ( rel.getOtherNode( underlyingNode ).equals( otherNode ) )
            {
                return rel;
            }
        }
        return null;
    }

    private Iterable<Person> getFriendsByDepth( int depth )
    {
        // return all my friends and their friends using new traversal API
        TraversalDescription travDesc = Traversal.description()
                .breadthFirst()
                .relationships( FRIEND )
                .uniqueness( Uniqueness.NODE_GLOBAL )
                .prune( Traversal.pruneAfterDepth( depth ) )
                .filter( Traversal.returnAllButStartNode() );

        return createPersonsFromPath( travDesc.traverse( underlyingNode ) );
    }

    private IterableWrapper<Person, Path> createPersonsFromPath(
            Traverser iterableToWrap )
    {
        return new IterableWrapper<Person, Path>( iterableToWrap )
        {
            @Override
            protected Person underlyingObjectToObject( Path path )
            {
                return new Person( path.endNode() );
            }
        };
    }

    private int getPathsToPerson( Person otherPerson )
    {
        PathFinder<Path> finder = GraphAlgoFactory.allPaths( Traversal.expanderForTypes( FRIEND, Direction.BOTH ), 2 );
        Iterable<Path> paths = finder.findAllPaths( getUnderlyingNode(), otherPerson.getUnderlyingNode() );
        return IteratorUtil.count( paths );
    }

    private Iterable<Person> createPersonsFromNodes( final Path path )
    {
        return new IterableWrapper<Person, Node>( path.nodes() )
        {
            @Override
            protected Person underlyingObjectToObject( Node node )
            {
                return new Person( node );
            }
        };
    }

}
