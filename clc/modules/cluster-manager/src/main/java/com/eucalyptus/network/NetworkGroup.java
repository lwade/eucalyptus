/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.network;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import java.util.Collection;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.EntityTransaction;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceContext;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.apache.log4j.Logger;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Entity;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import com.eucalyptus.cloud.CloudMetadata.NetworkSecurityGroup;
import com.eucalyptus.cloud.UserMetadata;
import com.eucalyptus.cloud.util.NotEnoughResourcesException;
import com.eucalyptus.cluster.VmInstance;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.entities.TransactionExecutionException;
import com.eucalyptus.records.Logs;
import com.eucalyptus.util.FullName;
import com.eucalyptus.util.Numbers;
import com.eucalyptus.util.OwnerFullName;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import edu.ucsb.eucalyptus.msgs.PacketFilterRule;

@Entity
@javax.persistence.Entity
@PersistenceContext( name = "eucalyptus_cloud" )
@Table( name = "metadata_network_group" )
@Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
public class NetworkGroup extends UserMetadata<NetworkGroup.State> implements NetworkSecurityGroup<NetworkGroup> {
  @Transient
  private static final long   serialVersionUID = 1L;
  @Transient
  private static final Logger LOG              = Logger.getLogger( NetworkGroup.class );
  
  public enum State {
    DISABLED,
    PENDING,
    AWAITING_PEER,
    ACTIVE
  }
  
  @Column( name = "metadata_network_group_description" )
  private String                description;
  
  @OneToMany( cascade = { CascadeType.ALL } )
  @Fetch( FetchMode.JOIN )
  @JoinTable( name = "metadata_network_group_has_rules", joinColumns = { @JoinColumn( name = "id" ) }, inverseJoinColumns = { @JoinColumn( name = "metadata_network_rule_id" ) } )
  @Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
  private Set<NetworkRule>      networkRules     = new HashSet<NetworkRule>( );
  
  @ManyToMany( cascade = { CascadeType.PERSIST, CascadeType.MERGE }, mappedBy = "networkGroups" )
  @Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
  private final Set<VmInstance> runningInstances = new HashSet<VmInstance>( );
  
  @OneToOne( cascade = { CascadeType.ALL }, mappedBy = "networkGroup", fetch = FetchType.EAGER )
  @Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
  private ExtantNetwork         extantNetwork;
  
  NetworkGroup( ) {}
  
  NetworkGroup( final OwnerFullName ownerFullName ) {
    super( ownerFullName );
  }
  
  NetworkGroup( final OwnerFullName ownerFullName, final String groupName ) {
    super( ownerFullName, groupName );
  }
  
  NetworkGroup( final OwnerFullName ownerFullName, final String groupName, final String groupDescription ) {
    this( ownerFullName, groupName );
    assertThat( groupDescription, notNullValue( ) );
    this.description = groupDescription;
  }
  
  public static NetworkGroup named( final OwnerFullName ownerFullName, final String groupName ) {
    return new NetworkGroup( ownerFullName, groupName );
  }
  
  /**
   * @param naturalId
   */
  private NetworkGroup( final String naturalId ) {
    this.setNaturalId( naturalId );
  }
  
  @PrePersist
  @PreUpdate
  private void prePersist( ) {
    if ( this.getState( ) == null ) {
      this.setState( State.PENDING );
    }
    
  }
  
  public String getDescription( ) {
    return this.description;
  }
  
  protected void setDescription( final String description ) {
    this.description = description;
  }
  
  public Set<NetworkRule> getNetworkRules( ) {
    return this.networkRules;
  }
  
  private void setNetworkRules( final Set<NetworkRule> networkRules ) {
    this.networkRules = networkRules;
  }
  
  //GRZE:NET
//  public Network getVmNetwork( ) {
//    Network asNet;
//    try {
//      asNet = Networks.getInstance( ).lookup( this );
//    } catch ( Exception ex ) {
//      Network vmNetwork = new Network( this );
//      asNet = Networks.getInstance( ).register( vmNetwork, Networks.State.ACTIVE );
//    }
//    return asNet;
//  }
  
  public Collection<PacketFilterRule> lookupPacketFilterRules( ) {
    final Collection<PacketFilterRule> pfRules = Collections2.transform( this.getNetworkRules( ), this.ruleTransform );
    return pfRules;
  }
  
  @Override
  public String getPartition( ) {
    return ComponentIds.lookup( Eucalyptus.class ).name( );
  }
  
  @Override
  public FullName getFullName( ) {
    return FullName.create.vendor( "euca" )
                          .region( ComponentIds.lookup( Eucalyptus.class ).name( ) )
                          .namespace( this.getOwnerAccountNumber( ) )
                          .relativeId( "security-group", this.getDisplayName( ) );
  }
  
  @Override
  public int hashCode( ) {
    final int prime = 31;
    int result = super.hashCode( );
    result = prime * result + ( ( this.getUniqueName( ) == null )
      ? 0
      : this.getUniqueName( ).hashCode( ) );
    return result;
  }
  
  @Override
  public boolean equals( final Object obj ) {
    if ( this == obj ) return true;
    if ( !super.equals( obj ) ) return false;
    if ( !this.getClass( ).equals( obj.getClass( ) ) ) return false;
    final NetworkGroup other = ( NetworkGroup ) obj;
    if ( this.getUniqueName( ) == null ) {
      if ( other.getUniqueName( ) != null ) return false;
    } else if ( !this.getUniqueName( ).equals( other.getUniqueName( ) ) ) return false;
    return true;
  }
  
  @Override
  public String toString( ) {
    return String.format( "NetworkRulesGroup:%s:description=%s:networkRules=%s", this.getUniqueName( ), this.description, this.networkRules );
  }
  
  @Transient
  private final Function<NetworkRule, PacketFilterRule> ruleTransform = new Function<NetworkRule, PacketFilterRule>( ) {
                                                                        
                                                                        @Override
                                                                        public PacketFilterRule apply( final NetworkRule from ) {
                                                                          final PacketFilterRule pfrule = new PacketFilterRule(
                                                                                                                                NetworkGroup.this.getOwnerAccountNumber( ),
                                                                                                                                NetworkGroup.this.getDisplayName( ),
                                                                                                                                from.getProtocol( ),
                                                                                                                                from.getLowPort( ),
                                                                                                                                from.getHighPort( ) );
                                                                          for ( final IpRange cidr : from.getIpRanges( ) )
                                                                            pfrule.getSourceCidrs( ).add( cidr.getValue( ) );
                                                                          for ( final NetworkPeer peer : from.getNetworkPeers( ) )
                                                                            pfrule.addPeer( peer.getUserQueryKey( ), peer.getGroupName( ) );
                                                                          return pfrule;
                                                                        }
                                                                      };
  
  @Override
  public int compareTo( final NetworkGroup that ) {
    return this.getUniqueName( ).compareTo( that.getUniqueName( ) );
  }
  
  public String getClusterNetworkName( ) {
    return this.getOwnerUserId( ) + "-" + this.getNaturalId( );
  }
  
  public ExtantNetwork extantNetwork( ) throws NotEnoughResourcesException {
    if ( !NetworkGroups.networkingConfiguration( ).hasNetworking( ) ) {
      return ExtantNetwork.bogus( this );
    } else {
      final EntityTransaction db = Entities.get( NetworkGroup.class );
      
      try {
        NetworkGroup net = Entities.merge( this );
        ExtantNetwork exNet = net.getExtantNetwork( );
        if ( net.getExtantNetwork( ) == null ) {
          exNet = this.findOrCreateExtantNetwork( );
        }
        return exNet;
      } catch ( TransactionException ex ) {
        Logs.extreme( ).trace( ex, ex );
        throw new NotEnoughResourcesException( ex );
      } catch ( NoSuchElementException ex ) {
        Logs.exhaust( ).trace( ex, ex );
        throw new NotEnoughResourcesException( ex );
      }
    }
  }
  
  private ExtantNetwork findOrCreateExtantNetwork( ) throws TransactionException, NoSuchElementException, NotEnoughResourcesException {
    EntityTransaction db = Entities.get( NetworkGroup.class );
    NetworkGroup ngNet = Entities.merge( this );
    ExtantNetwork exNet = null;
    try {
      exNet = ngNet.lookupExtantNetwork( );
    } catch ( Exception ex ) {
      Logs.extreme( ).error( ex, ex );
    }
    if ( exNet == null ) {
      exNet = ngNet.attemptNetworkTagging( );
    }
    if ( exNet != null ) {
      Entities.persist( exNet );
      this.setExtantNetwork( exNet );
      Entities.merge( ngNet );
      return exNet;
    } else {
      throw new NotEnoughResourcesException( "Cannot has." );
    }
  }
  
  private ExtantNetwork attemptNetworkTagging( ) throws NotEnoughResourcesException {
    for ( Integer i : Numbers.shuffled( NetworkGroups.networkTagInterval( ) ) ) {
      try {
        Entities.uniqueResult( ExtantNetwork.named( i ) );
        continue;
      } catch ( Exception ex ) {
        try {
          ExtantNetwork exNet = ExtantNetwork.create( this, i );
          exNet.setNetworkGroup( this );
          this.setExtantNetwork( exNet );
          Entities.persist( exNet );
          return Entities.merge( this ).extantNetwork;
        } catch ( Exception ex1 ) {
          Logs.exhaust( ).trace( ex1, ex1 );
          throw new NotEnoughResourcesException( "Failed to allocate network tag for network: " + this.getFullName( ), ex1 );
        }
      }
    }
    throw new NotEnoughResourcesException( "Failed to allocate network tag for network: " + this.getFullName( ) + ": no network tags are free." );
  }
  
  ExtantNetwork getExtantNetwork( ) {
    return this.extantNetwork;
  }
  
  private ExtantNetwork lookupExtantNetwork( ) throws TransactionException, NoSuchElementException {
    try {
      return Entities.uniqueResult( new ExtantNetwork( this, null ) );
    } catch ( Exception ex ) {
      throw new TransactionExecutionException( "Error occurred while attempting to lookup extant network for: " + this, ex );
    }
  }
  
  private void setExtantNetwork( final ExtantNetwork extantNetwork ) {
    extantNetwork.setNetworkGroup( this );
    this.extantNetwork = extantNetwork;
  }
  
  private Set<VmInstance> getRunningInstances( ) {
    return this.runningInstances;
  }
  
}
