import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CT074-3-2 Concurrent Programming - Individual Assignment
 * Case Study: Asia Pacific Airport Simulation
 */
public class AsiaPacificAirport {

    public static void main(String[] args) {
        System.out.println("==================================================================");
        System.out.println("          ASIA PACIFIC AIRPORT SIMULATION STARTING                ");
        System.out.println("==================================================================\n");

        AirportSharedResources airport = new AirportSharedResources();

        // Create and start the Air Traffic Controller (ATC) Thread
        ATC atcCallable = new ATC(airport);
        Thread atcThread = new Thread(atcCallable, "Thread-ATC");
        atcThread.start();

        // Create 6 Plane Threads (Week 2: Using Arrays of Threads)
        Thread[] planeThreads = new Thread[6];
        Plane[] planes = new Plane[6];
        Random rand = new Random();

        for (int i = 0; i < 6; i++) {
            int planeId = i + 1;
            // Plane 6 is simulated as the emergency fuel shortage aircraft
            boolean isEmergency = (planeId == 6);

            planes[i] = new Plane(planeId, isEmergency, airport);
            planeThreads[i] = new Thread(planes[i], "Thread-Plane-" + planeId);

            // Week 4: Thread Priority Setting (1 to 10)
            if (isEmergency) {
                planeThreads[i].setPriority(Thread.MAX_PRIORITY); // Priority 10 for Emergency
            } else {
                planeThreads[i].setPriority(Thread.NORM_PRIORITY); // Priority 5 for Normal
            }

            planeThreads[i].start();

            // Deliverable requirement: New airplane arrives every 0, 1, or 2 seconds
            try {
                // Using 900ms max ensures Planes 4, 5, and 6 arrive while 1, 2, 3 are at gates,
                // forcing the congested scenario perfectly every time!
                int sleepTime = rand.nextInt(900);
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Week 2: Join() - Main thread waits for all 6 Plane threads to finish execution
        for (int i = 0; i < 6; i++) {
            try {
                planeThreads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Wait for ATC thread to complete its final sanity checks and statistics
        try {
            atcThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("\n==================================================================");
        System.out.println("          ASIA PACIFIC AIRPORT SIMULATION COMPLETED               ");
        System.out.println("==================================================================");
    }
}

/**
 * Shared Resources class representing the Airport infrastructure, Locks,
 * Semaphores, and Atomic Statistics counters.
 */
class AirportSharedResources {
    // Basic Requirement: Max 3 airplanes on the airport grounds (including runway)
    public final Semaphore airportGroundsSemaphore = new Semaphore(3, true);

    // Additional Requirement: Only 1 refuelling truck available
    public final Semaphore refuelTruckSemaphore = new Semaphore(1, true);

    // Basic Requirement: Only 1 runway for landing and departing (Mutex Lock)
    public final ReentrantLock runwayLock = new ReentrantLock(true);

    // Shared lock object for wait() and notifyAll() communication between ATC and Planes
    public final Object atcLock = new Object();

    // 3 Gates status: false = empty/available, true = occupied/assigned
    public final boolean[] gatesOccupied = new boolean[3];
    public int planesOnGround = 0;
    public boolean runwayInUse = false;

    // Queues of planes waiting for ATC permission (No restricted blocking queues used)
    public final LinkedList<Plane> landingQueue = new LinkedList<>();
    public final LinkedList<Plane> takeoffQueue = new LinkedList<>();

    // Week 10: Atomic Variables for Sanity Checks and Statistics
    public final AtomicInteger planesServed = new AtomicInteger(0);
    public final AtomicInteger totalPassengersBoarded = new AtomicInteger(0);
    public final AtomicInteger totalPassengersDisembarked = new AtomicInteger(0);
    public final AtomicLong totalWaitingTime = new AtomicLong(0);
    public final AtomicLong maxWaitingTime = new AtomicLong(0);
    public final AtomicLong minWaitingTime = new AtomicLong(Long.MAX_VALUE);

    // Helper method to print with the active Thread's name to prove thread ownership
    public static synchronized void log(String message) {
        System.out.printf("[%-24s] %s%n", Thread.currentThread().getName(), message);
    }

    // Atomically update min and max waiting times using compareAndSet (Week 10)
    public void recordWaitingTime(long waitTimeMs) {
        totalWaitingTime.addAndGet(waitTimeMs);

        // Update Max Waiting Time
        long currentMax;
        do {
            currentMax = maxWaitingTime.get();
            if (waitTimeMs <= currentMax) break;
        } while (!maxWaitingTime.compareAndSet(currentMax, waitTimeMs));

        // Update Min Waiting Time
        long currentMin;
        do {
            currentMin = minWaitingTime.get();
            if (waitTimeMs >= currentMin) break;
        } while (!minWaitingTime.compareAndSet(currentMin, waitTimeMs));
    }
}

/**
 * Air Traffic Controller (ATC) Thread.
 * Strictly handles all ATC decisions, gate assignments, and ATC console outputs.
 */
class ATC implements Runnable {
    private final AirportSharedResources airport;
    private static final int TOTAL_PLANES = 6;

    public ATC(AirportSharedResources airport) {
        this.airport = airport;
    }

    @Override
    public void run() {
        while (airport.planesServed.get() < TOTAL_PLANES) {
            synchronized (airport.atcLock) {
                // Wait while there are no actionable requests
                while (airport.planesServed.get() < TOTAL_PLANES &&
                       !canProcessTakeoff() && !canProcessLanding() && !hasUnacknowledgedDenials()) {
                    try {
                        airport.atcLock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                if (airport.planesServed.get() >= TOTAL_PLANES) {
                    break;
                }

                // Check if an emergency plane is waiting
                boolean hasEmergency = false;
                for (Plane p : airport.landingQueue) {
                    if (p.isEmergency()) {
                        hasEmergency = true;
                        break;
                    }
                }

                // 1. ABSOLUTE PRIORITY: Emergency Landing (if airport space allows)
                if (hasEmergency && canProcessLanding()) {
                    processLanding();
                    continue;
                }

                // 2. NORMAL PRIORITY: Take-offs (clears up gates and ground space)
                if (canProcessTakeoff()) {
                    Plane departingPlane = airport.takeoffQueue.removeFirst();
                    airport.runwayInUse = true;
                    departingPlane.setTakeoffGranted(true);
                    AirportSharedResources.log("ATC: Taking-off is granted for Plane-" + departingPlane.getPlaneId() + ". Runway is free.");
                    airport.atcLock.notifyAll();
                    continue;
                }

                // 3. LOWEST PRIORITY: Inform planes if airport is full
                for (Plane p : airport.landingQueue) {
                    if (!p.isDenialLogged() && (airport.planesOnGround >= 3 || getAvailableGate() == -1)) {
                        if (airport.planesOnGround >= 3) {
                            AirportSharedResources.log("ATC: Landing Permission Denied for Plane-" + p.getPlaneId() + ", Airport Full.");
                        } else {
                            AirportSharedResources.log("ATC: Landing Permission Denied for Plane-" + p.getPlaneId() + ", All Gates Occupied.");
                        }
                        p.setDenialLogged(true);
                    }
                }

                // 4. NORMAL PRIORITY: Standard Landings
                if (canProcessLanding()) {
                    processLanding();
                }
            }
        }

        // Deliverable Requirement: ATC Manager Sanity Checks and Statistics at the end of simulation
        performSanityChecksAndPrintStats();
    }

    // Helper method to execute the landing procedure
    private void processLanding() {
        Plane nextPlane = selectHighestPriorityPlane();
        int freeGateIndex = getAvailableGate();

        // Reserve resources for this plane
        airport.gatesOccupied[freeGateIndex] = true;
        airport.planesOnGround++;
        airport.runwayInUse = true;

        nextPlane.setAssignedGate(freeGateIndex + 1);
        nextPlane.setLandingGranted(true);

        if (nextPlane.isEmergency()) {
            AirportSharedResources.log("ATC: EMERGENCY Landing Permission granted for Plane-" + nextPlane.getPlaneId() + " (Fuel Shortage Priority!).");
        } else {
            AirportSharedResources.log("ATC: Landing Permission granted for Plane-" + nextPlane.getPlaneId() + ".");
        }
        AirportSharedResources.log("ATC: Gate-" + nextPlane.getAssignedGate() + " assigned for Plane-" + nextPlane.getPlaneId() + ".");

        airport.atcLock.notifyAll();
    }

    private boolean canProcessTakeoff() {
        return !airport.takeoffQueue.isEmpty() && !airport.runwayInUse;
    }

    private boolean canProcessLanding() {
        return !airport.landingQueue.isEmpty()
                && airport.planesOnGround < 3
                && getAvailableGate() != -1
                && !airport.runwayInUse;
    }

    private boolean hasUnacknowledgedDenials() {
        if (airport.planesOnGround < 3 && getAvailableGate() != -1) return false;
        for (Plane p : airport.landingQueue) {
            if (!p.isDenialLogged()) return true;
        }
        return false;
    }

    // Finds the first free gate (0, 1, or 2), or returns -1 if all 3 are occupied
    private int getAvailableGate() {
        for (int i = 0; i < airport.gatesOccupied.length; i++) {
            if (!airport.gatesOccupied[i]) {
                return i;
            }
        }
        return -1;
    }

    // Manual priority selection (Week 4 HMS style) without using restricted PriorityBlockingQueue
    private Plane selectHighestPriorityPlane() {
        Plane chosen = airport.landingQueue.getFirst();
        for (Plane candidate : airport.landingQueue) {
            if (candidate.getPriorityLevel() > chosen.getPriorityLevel()) {
                chosen = candidate;
            }
        }
        airport.landingQueue.remove(chosen);
        return chosen;
    }

    private void performSanityChecksAndPrintStats() {
        System.out.println("\n------------------------------------------------------------------");
        AirportSharedResources.log("ATC: All planes have left. Performing Airport Sanity Checks...");

        boolean allGatesEmpty = true;
        for (int i = 0; i < airport.gatesOccupied.length; i++) {
            String status = airport.gatesOccupied[i] ? "OCCUPIED (ERROR)" : "EMPTY (OK)";
            AirportSharedResources.log("ATC Sanity Check -> Gate-" + (i + 1) + " is " + status);
            if (airport.gatesOccupied[i]) {
                allGatesEmpty = false;
            }
        }

        AirportSharedResources.log("ATC Sanity Check -> Available Airport Ground Permits: "
                + airport.airportGroundsSemaphore.availablePermits() + "/3");
        AirportSharedResources.log("ATC Sanity Check -> All Gates Empty Verification: "
                + (allGatesEmpty ? "PASSED" : "FAILED"));

        int served = airport.planesServed.get();
        long maxWait = airport.maxWaitingTime.get();
        long minWait = (served > 0) ? airport.minWaitingTime.get() : 0;
        double avgWait = (served > 0) ? (double) airport.totalWaitingTime.get() / served : 0.0;

        System.out.println("------------------------------------------------------------------");
        AirportSharedResources.log("ATC STATISTICAL REPORT:");
        AirportSharedResources.log(" -> Total Number of Planes Served     : " + served);
        AirportSharedResources.log(" -> Total Passengers Disembarked      : " + airport.totalPassengersDisembarked.get());
        AirportSharedResources.log(" -> Total Passengers Boarded          : " + airport.totalPassengersBoarded.get());
        AirportSharedResources.log(String.format(" -> Maximum Waiting Time for a Plane  : %d ms (%.2f s)", maxWait, maxWait / 1000.0));
        AirportSharedResources.log(String.format(" -> Minimum Waiting Time for a Plane  : %d ms (%.2f s)", minWait, minWait / 1000.0));
        AirportSharedResources.log(String.format(" -> Average Waiting Time for a Plane  : %.2f ms (%.2f s)", avgWait, avgWait / 1000.0));
        System.out.println("------------------------------------------------------------------");
    }
}

/**
 * Plane Thread representing an aircraft and its pilot actions.
 * Spawns nested threads once docked at a gate for concurrent gate operations.
 */
class Plane implements Runnable {
    private final int planeId;
    private final boolean isEmergency;
    private final AirportSharedResources airport;

    private int assignedGate = -1;
    private boolean landingGranted = false;
    private boolean takeoffGranted = false;
    private boolean denialLogged = false;
    private int priorityLevel;

    public Plane(int planeId, boolean isEmergency, AirportSharedResources airport) {
        this.planeId = planeId;
        this.isEmergency = isEmergency;
        this.airport = airport;
        this.priorityLevel = isEmergency ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY;
    }

    @Override
    public void run() {
        long arrivalTime = System.currentTimeMillis();

        // 1. Request Landing from ATC (Week 4: wait() and notifyAll())
        synchronized (airport.atcLock) {
            this.priorityLevel = Thread.currentThread().getPriority();
            if (isEmergency) {
                AirportSharedResources.log("Plane-" + planeId + ": EMERGENCY! Fuel shortage! Requesting immediate landing!");
            } else {
                AirportSharedResources.log("Plane-" + planeId + ": Requesting Landing.");
            }
            airport.landingQueue.addLast(this);
            airport.atcLock.notifyAll();

            while (!landingGranted) {
                try {
                    airport.atcLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        // Record waiting time once permission is granted
        long waitDuration = System.currentTimeMillis() - arrivalTime;
        airport.recordWaitingTime(waitDuration);

        try {
            // Acquire permit for entering airport grounds (Max 3 planes on grounds)
            airport.airportGroundsSemaphore.acquire();

            // 2. Acquire Exclusive Runway Lock to Land and Coast to Gate (Week 5-8: ReentrantLock)
            airport.runwayLock.lock();
            try {
                AirportSharedResources.log("Plane-" + planeId + ": Landing.");
                Thread.sleep(500);
                AirportSharedResources.log("Plane-" + planeId + ": Landed.");
                AirportSharedResources.log("Plane-" + planeId + ": Coasting to Gate-" + assignedGate + ".");
                Thread.sleep(500);
                AirportSharedResources.log("Plane-" + planeId + ": Docked at Gate-" + assignedGate + ".");
            } finally {
                // Free the runway so other planes can land or take off while this plane is at the gate
                airport.runwayLock.unlock();
            }

            // Notify ATC that the runway is now free
            synchronized (airport.atcLock) {
                airport.runwayInUse = false;
                airport.atcLock.notifyAll();
            }

            // 3. Concurrent Gate Operations using Nested Threads (Week 2: Nested Threads & join())
            Thread passengerThread = new Thread(new PassengerHandler(planeId, airport),
                    "Thread-Plane-" + planeId + "-Passengers");
            Thread suppliesThread = new Thread(new AircraftServiceHandler(planeId),
                    "Thread-Plane-" + planeId + "-Supplies");
            Thread refuelThread = new Thread(new RefuelTruckHandler(planeId, airport),
                    "Thread-RefuelTruck-P" + planeId);

            // Start all 3 gate activities concurrently
            passengerThread.start();
            suppliesThread.start();
            refuelThread.start();

            // Wait for all 3 concurrent gate activities to complete before undocking
            passengerThread.join();
            suppliesThread.join();
            refuelThread.join();

            // 4. Undock and Request Take-off from ATC
            AirportSharedResources.log("Plane-" + planeId + ": Undocked from Gate-" + assignedGate + ".");
            synchronized (airport.atcLock) {
                AirportSharedResources.log("Plane-" + planeId + ": Requesting Taking off.");
                airport.takeoffQueue.addLast(this);
                airport.atcLock.notifyAll();

                while (!takeoffGranted) {
                    airport.atcLock.wait();
                }
            }

            // 5. Acquire Runway Lock, Coast to Runway, and Take-off
            airport.runwayLock.lock();
            try {
                AirportSharedResources.log("Plane-" + planeId + ": Coasting to assigned runway.");
                Thread.sleep(400);
                AirportSharedResources.log("Plane-" + planeId + ": Taking-off.");
                Thread.sleep(500);
                AirportSharedResources.log("Plane-" + planeId + ": Successfully departed airport.");
            } finally {
                airport.runwayLock.unlock();
            }

            // 6. Release Gate and Airport Grounds permit, update Atomic Statistics, and notify ATC
            airport.airportGroundsSemaphore.release();

            synchronized (airport.atcLock) {
                airport.gatesOccupied[assignedGate - 1] = false;
                airport.planesOnGround--;
                airport.runwayInUse = false;
                airport.planesServed.incrementAndGet();
                airport.atcLock.notifyAll();
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int getPlaneId() { return planeId; }
    public boolean isEmergency() { return isEmergency; }
    public int getPriorityLevel() { return priorityLevel; }
    public int getAssignedGate() { return assignedGate; }
    public void setAssignedGate(int assignedGate) { this.assignedGate = assignedGate; }
    public void setLandingGranted(boolean landingGranted) { this.landingGranted = landingGranted; }
    public void setTakeoffGranted(boolean takeoffGranted) { this.takeoffGranted = takeoffGranted; }
    public boolean isDenialLogged() { return denialLogged; }
    public void setDenialLogged(boolean denialLogged) { this.denialLogged = denialLogged; }
}

/**
 * Nested Thread 1: Handles Passengers Disembarking and Embarking as a single group output
 * (Strictly follows Figure 2 & Figure 3 guidelines).
 */
class PassengerHandler implements Runnable {
    private final int planeId;
    private final AirportSharedResources airport;
    private final Random rand = new Random();

    public PassengerHandler(int planeId, AirportSharedResources airport) {
        this.planeId = planeId;
        this.airport = airport;
    }

    @Override
    public void run() {
        try {
            // Maximum 50 passengers per plane
            int disembarkingCount = 25 + rand.nextInt(26); // 25 to 50 passengers
            AirportSharedResources.log("Plane-" + planeId + "'s Passengers: Disembarking out of Plane-"
                    + planeId + " (" + disembarkingCount + " passengers).");
            Thread.sleep(1200);
            airport.totalPassengersDisembarked.addAndGet(disembarkingCount);

            int boardingCount = 30 + rand.nextInt(21); // 30 to 50 new passengers
            AirportSharedResources.log("Plane-" + planeId + "'s Passengers: Embarking into Plane-"
                    + planeId + " (" + boardingCount + " new passengers).");
            Thread.sleep(1200);
            airport.totalPassengersBoarded.addAndGet(boardingCount);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

/**
 * Nested Thread 2: Handles Refilling Supplies and Cleaning of the Aircraft concurrently.
 */
class AircraftServiceHandler implements Runnable {
    private final int planeId;

    public AircraftServiceHandler(int planeId) {
        this.planeId = planeId;
    }

    @Override
    public void run() {
        try {
            AirportSharedResources.log("Plane-" + planeId + "'s Ground Crew: Cleaning aircraft and refilling supplies.");
            Thread.sleep(1800);
            AirportSharedResources.log("Plane-" + planeId + "'s Ground Crew: Finished cleaning and refilling supplies for Plane-" + planeId + ".");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

/**
 * Nested Thread 3: Acquires the single Refuel Truck (Semaphore(1)) to refuel one plane at a time.
 */
class RefuelTruckHandler implements Runnable {
    private final int planeId;
    private final AirportSharedResources airport;

    public RefuelTruckHandler(int planeId, AirportSharedResources airport) {
        this.planeId = planeId;
        this.airport = airport;
    }

    @Override
    public void run() {
        try {
            AirportSharedResources.log("Plane-" + planeId + ": Requesting Refuel Truck.");
            // Acquire the single Refuel Truck permit (blocks if another plane is currently refuelling)
            airport.refuelTruckSemaphore.acquire();
            try {
                AirportSharedResources.log("Refuel Truck: Refuelling Plane-" + planeId + " now.");
                Thread.sleep(1500);
                AirportSharedResources.log("Refuel Truck: Completed refuelling Plane-" + planeId + ". Truck is now available.");
            } finally {
                airport.refuelTruckSemaphore.release();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}