/*
 * Copyright IBM Corp. and others 2026
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] https://openjdk.org/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR GPL-2.0-only WITH OpenJDK-assembly-exception-1.0
 */
package com.ibm.j9ddr.vm29.j9;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.ibm.j9ddr.CorruptDataException;
import com.ibm.j9ddr.vm29.j9.DataType;
import com.ibm.j9ddr.vm29.pointer.generated.J9JavaVMPointer;
import com.ibm.j9ddr.vm29.pointer.generated.J9ObjectMonitorPointer;
import com.ibm.j9ddr.vm29.pointer.generated.J9ObjectPointer;
import com.ibm.j9ddr.vm29.pointer.generated.J9VMContinuationPointer;
import com.ibm.j9ddr.vm29.pointer.generated.J9VMThreadPointer;
import com.ibm.j9ddr.vm29.pointer.helper.J9RASHelper;

/**
 * ObjectMonitor implementation for JEP491-enabled VMs (Java 24+).
 *
 * Extends ObjectMonitor_V2 with support for monitors owned or waited on by unmounted virtual threads.
 * When a virtual thread is unmounted while holding a monitor, the owning continuation
 * is recorded in J9ObjectMonitor.ownerContinuation rather than via an OS thread owner.
 */
class ObjectMonitor_V3 extends ObjectMonitor_V2
{
	private J9VMContinuationPointer ownerContinuation = J9VMContinuationPointer.NULL;
	private long virtualThreadWaitCount = 0;

	private ArrayList<J9VMContinuationPointer> blockedContinuations;
	private ArrayList<J9VMContinuationPointer> waitingContinuations;

	/* Cache for enter-waiters across all monitors — built once from vm.blockedContinuations */
	private static HashMap<J9ObjectMonitorPointer, List<J9VMContinuationPointer>> blockedContsCache;

	/* Do not instantiate. Use the factory */
	protected ObjectMonitor_V3(J9ObjectPointer object) throws CorruptDataException
	{
		super(object);
		if (isInflated()) {
			J9ObjectMonitorPointer j9mon = getJ9ObjectMonitorPointer();
			if (j9mon.notNull()) {
				try {
					J9VMContinuationPointer cont = j9mon.ownerContinuation();
					if (cont.notNull()) {
						ownerContinuation = cont;
					}
					virtualThreadWaitCount = j9mon.virtualThreadWaitCount().longValue();
				} catch (CorruptDataException | NoSuchFieldException e) {
					/* JEP491 fields unavailable in this VM build */
				}
			}
		}
	}

	@Override
	public J9VMThreadPointer getOwner() throws CorruptDataException
	{
		if (isOwnedByUnmountedVThread()) {
			return J9VMThreadPointer.NULL;
		}
		return super.getOwner();
	}

	@Override
	public J9VMContinuationPointer getOwnerContinuation() throws CorruptDataException
	{
		return ownerContinuation;
	}

	@Override
	public boolean isOwnedByUnmountedVThread() throws CorruptDataException
	{
		return ownerContinuation.notNull();
	}

	@Override
	public long getVirtualThreadWaitCount() throws CorruptDataException
	{
		return virtualThreadWaitCount;
	}

	@Override
	public List<J9VMContinuationPointer> getBlockedContinuations() throws CorruptDataException
	{
		if (blockedContinuations == null) {
			initializeBlockedContinuations();
		}
		return blockedContinuations;
	}

	@Override
	public List<J9VMContinuationPointer> getWaitingContinuations() throws CorruptDataException
	{
		if (waitingContinuations == null) {
			initializeWaitingContinuations();
		}
		return waitingContinuations;
	}

	private void initializeBlockedContinuations() throws CorruptDataException
	{
		blockedContinuations = new ArrayList<>();
		if (blockedContsCache == null) {
			blockedContsCache = new HashMap<>();
			try {
				J9JavaVMPointer vm = J9RASHelper.getVM(DataType.getJ9RASPointer());
				J9VMContinuationPointer cont = vm.blockedContinuations();
				while (cont.notNull()) {
					J9ObjectMonitorPointer monPtr = cont.objectWaitMonitor();
					if (monPtr.notNull()) {
						List<J9VMContinuationPointer> list = blockedContsCache.get(monPtr);
						if (list == null) {
							list = new ArrayList<>();
							blockedContsCache.put(monPtr, list);
						}
						list.add(cont);
					}
					cont = cont.nextWaitingContinuation();
				}
			} catch (NoSuchFieldException e) {
				/* blockedContinuations unavailable in this VM build */
			}
		}
		List<J9VMContinuationPointer> cached = blockedContsCache.get(getJ9ObjectMonitorPointer());
		if (cached != null) {
			blockedContinuations.addAll(cached);
		}
	}

	private void initializeWaitingContinuations() throws CorruptDataException
	{
		waitingContinuations = new ArrayList<>();
		if (!isInflated()) {
			return;
		}
		J9ObjectMonitorPointer j9mon = getJ9ObjectMonitorPointer();
		if (j9mon.notNull()) {
			try {
				J9VMContinuationPointer cont = j9mon.waitingContinuations();
				while (cont.notNull()) {
					waitingContinuations.add(cont);
					cont = cont.nextWaitingContinuation();
				}
			} catch (NoSuchFieldException e) {
				/* waitingContinuations unavailable in this VM build */
			}
		}
	}

}
