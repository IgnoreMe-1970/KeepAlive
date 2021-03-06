/*
 * Keep Alive Plugin
 * Copyright (C) 2012 Jeriadoc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package keepalive;

public class SingleJob extends Thread {

	static final int MAX_LIFETIME = 30;
	Reinserter reinserter;
	String cJobType;
	Plugin plugin;
	Block block;
	boolean bActive = true;
	byte[] aExtra;
	String cCompressor;

	public SingleJob(Reinserter reinserter, String cJobType, Block block) {

		this.reinserter = reinserter;
		this.cJobType = cJobType;
		this.block = block;
		this.plugin = reinserter.plugin;
		this.setName("KeepAlive SingleJob");

		// init
		reinserter.nActiveSingleJobCount++;
	}

	@Override
	public void run() {
		try {

			// start lifetime guard
			(new ActivityGuard(this, cJobType)).start();

		} catch (Exception e) {
			plugin.log("singleJob.run(): " + e.getMessage(), 0);
		}
	}

	protected void finish() {
		try {

			if (reinserter.isActive()) {

				// log
				String cFirstLog = cJobType + ": " + block.uri;
				if (!block.bFetchSuccessfull && !block.bInsertSuccessfull) {
					cFirstLog = "<b>" + cFirstLog + "</b>";
					block.cResultLog = "<b>" + block.cResultLog + "</b>";
				}
				log(cFirstLog);
				log(block.cResultLog);

				// finish
				reinserter.nActiveSingleJobCount--;

			}

		} catch (Exception e) {
			plugin.log("singleJob.finish(): " + e.getMessage(), 0);
		}
	}

	protected void log(String cMessage, int nLogLevel) {
		if (reinserter.isActive()) {
			reinserter.log(block.nSegmentId, cMessage, 0, nLogLevel);
		}
	}

	protected void log(String cMessage) {
		log(cMessage, 1);
	}

	private class ActivityGuard extends Thread {

		private final SingleJob singleJob;
		private final long nStartTime;
		private final String cType;

		public ActivityGuard(SingleJob singleJob, String cType) {
			this.singleJob = singleJob;
			this.cType = cType;
			nStartTime = System.currentTimeMillis();
		}

		@Override
		public synchronized void run() {
			try {

				// stop
				while (reinserter.isActive() && singleJob.isAlive() && getLifetime() < MAX_LIFETIME) {
					wait(1000);
				}

				// has timeout stop 
				if (reinserter.isActive() && singleJob.isAlive()) {
					singleJob.stop();
					singleJob.block.cResultLog += "<b>-> " + cJobType + " aborted (timeout)</b>";
				}

				// has stopped after reinserter stop
				if (!reinserter.isActive()) {
					singleJob.stop();
					long nStopCheckBegin = System.currentTimeMillis();
					while (singleJob.isAlive() && nStopCheckBegin > System.currentTimeMillis() - 60 * 1000) {
						try {
							wait(1000);
						} catch (InterruptedException e) {
						}
					}
					if (!singleJob.isAlive()) {
						plugin.log("single " + cType + " stopped (" + singleJob.reinserter.nSiteId + ")");
					} else {
						plugin.log("single " + cType + " not stopped  - stop was indicated 1 minute before (" + singleJob.reinserter.nSiteId + ")");
					}
				}

			} catch (InterruptedException e) {
				plugin.log("singleJob.ActivityGuard.run(): " + e.getMessage(), 0);
			}
		}

		private long getLifetime() {
			return (System.currentTimeMillis() - nStartTime) / 60 / 1000;
		}
	}
}
