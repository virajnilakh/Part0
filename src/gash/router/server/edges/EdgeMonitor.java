/**
 * Copyright 2016 Gash.
 *
 * This file and intellectual content is protected under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package gash.router.server.edges;

import java.util.HashMap;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import gash.router.client.CommConnection;
import gash.router.container.RoutingConf.RoutingEntry;
import gash.router.election.ElectionHandler;
import gash.router.server.CommandInit;
import gash.router.server.ServerState;
import gash.router.server.WorkInit;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import pipe.common.Common.Header;
import pipe.election.Election.LeaderStatus;
import pipe.election.Election.LeaderStatus.LeaderState;
import pipe.work.Work.Heartbeat;
import pipe.work.Work.WorkMessage;
import pipe.work.Work.WorkState;
import routing.Pipe.CommandMessage;

public class EdgeMonitor implements EdgeListener, Runnable {
	protected static Logger logger = LoggerFactory.getLogger("edge monitor");
	private HashMap<Integer,Timer> timer=new HashMap<Integer,Timer>();
	private static EdgeList outboundEdges;
	private EdgeList inboundEdges;
	private long dt = 50;
	private ServerState state;
	private boolean forever = true;
	private int activeOutboundEdges=0;
	public HashMap<Integer,EdgeInfo> getOutboundEdges() {
		return outboundEdges.getMap();
	}

	public EdgeMonitor(ServerState state) {
		if (state == null)
			throw new RuntimeException("state is null");

		this.outboundEdges = new EdgeList();
		this.inboundEdges = new EdgeList();
		this.state = state;
		this.state.setEmon(this);

		if (state.getConf().getRouting() != null) {
			
			for (RoutingEntry e : state.getConf().getRouting()) {
				
				outboundEdges.addNode(e.getId(), e.getHost(), e.getPort());
			}
		}

		// cannot go below 2 sec
		if (state.getConf().getHeartbeatDt() > this.dt)
			this.dt = state.getConf().getHeartbeatDt();
	}
	

	public void sendData(CommandMessage msg){
		for (EdgeInfo ei : this.outboundEdges.map.values()){
			Channel ch=ei.getChannel();
			ChannelFuture cf = ch.writeAndFlush(msg);
			if (cf.isDone() && !cf.isSuccess()) {
				logger.error("failed to send message to server");

			}
		}
	}
	public void createInboundIfNew(int ref, String host, int port) {
		inboundEdges.createIfNew(ref, host, port);
	}
	public void broadcast(WorkMessage msg){
			for (EdgeInfo ei : this.outboundEdges.map.values()){
				Channel ch=ei.getChannel();
				ChannelFuture cf =null;
				if(ch!=null){
					cf= ch.write(msg);
					ch.flush();
					if (cf.isDone() && !cf.isSuccess()) {
						logger.info("failed to send vote to server");

					}
				}else{
					ei.setActive(false);
					activeOutboundEdges--;
				}
				
				
			}
		}
	public void broadcast(CommandMessage msg){
		for (EdgeInfo ei : this.outboundEdges.map.values()){
			Channel ch=ei.getChannel();
			ChannelFuture cf =null;
			if(ch!=null){
				cf= ch.write(msg);
				ch.flush();
				if (cf.isDone() && !cf.isSuccess()) {
					logger.info("failed to send vote to server");

				}
			}else{
				ei.setActive(false);
				activeOutboundEdges--;
			}
			
			
		}
	}
	private WorkMessage createHB(EdgeInfo ei) {
		WorkState.Builder sb = WorkState.newBuilder();
		sb.setEnqueued(-1);
		sb.setProcessed(-1);

		Heartbeat.Builder bb = Heartbeat.newBuilder();
		bb.setState(sb);

		Header.Builder hb = Header.newBuilder();
		hb.setNodeId(state.getConf().getNodeId());
		hb.setDestination(-1);
		hb.setTime(System.currentTimeMillis());
		LeaderStatus.Builder status=LeaderStatus.newBuilder();
		status.setLeaderId(state.getConf().getNodeId());
		status.setLeaderHost(state.getIpAddress());
		WorkMessage.Builder wb = WorkMessage.newBuilder();
		wb.setHeader(hb);
		wb.setSecret(0);
		wb.setBeat(bb);
		if (state.getLeaderId() == 0) {
			status.setState(LeaderState.LEADERUNKNOWN);
			wb.setLeaderStatus(status);
		} else if (state.getLeaderId() == state.getConf().getNodeId()) {
			status.setState(LeaderState.LEADERALIVE);
			wb.setLeaderStatus(status);
		} else {
			status.setState(LeaderState.LEADERKNOWN);
			wb.setLeaderStatus(status);
		}
		return wb.build();
	}
	public WorkMessage createHB(int nodeId) {
		WorkState.Builder sb = WorkState.newBuilder();
		sb.setEnqueued(-1);
		sb.setProcessed(-1);

		Heartbeat.Builder bb = Heartbeat.newBuilder();
		bb.setState(sb);

		Header.Builder hb = Header.newBuilder();
		hb.setNodeId(state.getConf().getNodeId());
		hb.setDestination(-1);
		hb.setTime(System.currentTimeMillis());
		LeaderStatus.Builder status=LeaderStatus.newBuilder();
		status.setLeaderId(state.getConf().getNodeId());
		WorkMessage.Builder wb = WorkMessage.newBuilder();
		wb.setHeader(hb);
		wb.setSecret(0);
		wb.setBeat(bb);
		if(state.getLeaderId()!=0){
			status.setState(LeaderState.LEADERKNOWN);
		}else{
			status.setState(LeaderState.LEADERUNKNOWN);
		}
		wb.setLeaderStatus(status);
		
		return wb.build();
	}

	public void shutdown() {
		forever = false;
	}

	@Override
	public void run() {
		while (forever) {
			try {
				for (EdgeInfo ei : this.outboundEdges.map.values()) {
					
					
					if (ei.isActive() && ei.getChannel() != null) {
						WorkMessage wmhb=state.getEmon().createHB(state.getConf().getNodeId());
						ei.getChannel().writeAndFlush(wmhb);
						if(state.isLeader()){
							WorkMessage wm = createHB(ei);
							System.out.println("---Leader "+state.getLeaderId()+" sending heartbeat---");

							ei.getChannel().writeAndFlush(wm);
						}
						if(activeOutboundEdges==outboundEdges.map.size() && state.getLeaderId()==0){
							state.getElecHandler().initElection();
						}else{
							if(state.getLeaderId()!=0){
								System.out.println("---Current Leader is:"+state.getLeaderId()+"---");

							}else{
								System.out.println("No proper connections");
								

							}
						}
						
					} else {
						// TODO create a client to the node
						logger.info("trying to connect to node " + ei.getRef());
						String host = ei.getHost();
				        int port = ei.getPort();
				        EventLoopGroup workerGroup = new NioEventLoopGroup();

				        try {
				            Bootstrap b = new Bootstrap(); // (1)
				            b.group(workerGroup); // (2)
				            b.channel(NioSocketChannel.class); // (3)
				            b.option(ChannelOption.SO_KEEPALIVE, true); // (4)
				            b.handler(new WorkInit(state,false));

				            // Start the client.
				            //System.out.println("Connect to a node.");

				            ChannelFuture f = b.connect(host, port).sync();
				            ei.setChannel(f.channel());
				            ei.setActive(true);
				            activeOutboundEdges++;
				            /*f.addListener(new FutureListener<Void>() {

				                @Override
				                public void operationComplete(Future<Void> future) throws Exception {
				                    if (!f.isSuccess()) {
				                        System.out.println("Test Connection failed");
				                        future.cause();

				                    }else{
				                    	setTimer(ei.getRef());
				                    	ei.setChannel(f.channel());
							            ei.setActive(true);
							            activeOutboundEdges++;
				                    }
				                }
				            });		*/	            
				            
				            // Wait until the connection is closed.
				            //f.channel().closeFuture().sync();
				        } catch(Exception e) {
				        	e.printStackTrace();
				            //workerGroup.shutdownGracefully();
				        }

					}
				}

				Thread.sleep(dt);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
private static class DeadFollowerTimer extends TimerTask{
		private int nodeId;
        public DeadFollowerTimer(int nodeId){
        	this.nodeId=nodeId;
        }
        @Override
        public void run(){
        	System.out.println("Node "+nodeId+"dead");
        	outboundEdges.map.get(nodeId).setChannel(null);;
        	outboundEdges.map.get(nodeId).setActive(false);
        	this.cancel();
        }
    }
	@Override
	public synchronized void onAdd(EdgeInfo ei) {
		// TODO check connection
	}

	@Override
	public synchronized void onRemove(EdgeInfo ei) {
		// TODO ?
	}

	public Timer getTimer(int nodeId) {
		return timer.get(nodeId);
	}

	public void setTimer(int nodeId) {
		int randomTimeout=(2000+(new Random()).nextInt(3500))*7;
        Timer t=new Timer();
        t.schedule(new DeadFollowerTimer(nodeId),(long)randomTimeout,(long)randomTimeout);
		timer.put(nodeId,t );
	}
}
