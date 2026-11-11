# asia-pacific-airport-simulation
A Java concurrency simulation of six aircraft sharing one runway, three gates, and one refuelling truck, with landing priority for a low-fuel aircraft.
# Asia Pacific Airport Simulation

A Java console simulation of six aircraft sharing one runway, three gates, and one refuelling truck. I built it as an individual assignment for the Concurrent Programming module at Asia Pacific University.

## How it works

- One air traffic controller thread manages landing and take-off queues. It prioritises the low-fuel aircraft for landing when the runway and airport capacity allow it.
- Each aircraft runs on its own thread. At the gate, passenger handling, cabin servicing, and refuelling run concurrently. The aircraft waits for all three tasks before requesting take-off.
- Semaphores limit ground capacity to three aircraft and refuelling to one aircraft at a time. A `ReentrantLock` controls access to the runway. The controller and aircraft coordinate through `wait()` and `notifyAll()`.
- Atomic counters record aircraft served, passenger totals, and waiting times. The closing report checks that the gates are empty and shows how many ground permits are available.

## Run locally

Install a Java Development Kit and open a terminal in the folder containing `AsiaPacificAirport.java`. This project uses Java's standard library and was tested with JDK 21.

```sh
javac -encoding UTF-8 AsiaPacificAirport.java
java AsiaPacificAirport
```

Watch the console for gate assignments, emergency clearance for Plane 6, and the final statistics. Arrival timing and passenger counts vary between runs.
