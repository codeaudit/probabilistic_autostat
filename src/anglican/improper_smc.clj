(ns anglican.improper-smc
  "Like SMC, except we 'unobserve' everything we sample"
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:use anglican.state
        anglican.inference
        [anglican.runtime :only [sample observe]]))

(derive ::algorithm :anglican.inference/algorithm)

(defmethod checkpoint [::algorithm anglican.trap.observe] [_ obs]
  ;; update the weight and return the observation checkpoint
  ;; for possible resampling
  (update-in obs [:state]
             add-log-weight (:value obs)))

(defmethod checkpoint [::algorithm anglican.trap.sample] [_ smp]
  (let [value (sample (:dist smp))]
    #((:cont smp) value (add-log-weight (:state smp)
                                    (- (observe (:dist smp) value))))))

(declare resample)
(declare particle-weights)

(defn resample-and-print [particles number-of-particles]
  (let [resampled-particles (resample particles number-of-particles)
        values (map #(:value %) resampled-particles)
        avg-val (/ (reduce + values) (count values))]
    (print (str (Math/round avg-val) ","))
    (flush)
    resampled-particles))

(defmulti sweep
  "a single sweep of SMC and friends"
  (fn [algorithm prog value number-of-particles & _] algorithm))

(defmethod sweep ::algorithm
  [algorithm prog value number-of-particles]
  (loop [particles (apply pcalls (repeat number-of-particles
                               #(exec algorithm
                                      prog value initial-state)))]
    (cond
     (every? #(instance? anglican.trap.observe %) particles)
     (recur (doall (pmap #(exec algorithm (:cont %) nil (:state %))
                         (resample-and-print particles number-of-particles))))

     (every? #(instance? anglican.trap.result %) particles)
     particles

     :else (throw (AssertionError.
                   "some `observe' directives are not global")))))

(defmethod infer :improper-smc [_ prog value
                                & {:keys [number-of-particles]   ; per sweep
                                   :or {number-of-particles 1}}]
  (assert (>= number-of-particles 1)
          ":number-of-particles must be at least 1")
  (letfn [(sample-seq []
            (lazy-seq
             (let [particles (sweep ::algorithm
                                    prog value number-of-particles)]
               (concat (doall (pmap :state particles)) (sample-seq)))))]
    (sample-seq)))

(defn particle-weights
  "computes relative particle weights;
  returns tuple [weights/max-weight max-log-weight]"
  [particles]
  (let [log-weights (map (comp get-log-weight :state)
                         particles)
        max-log-weight (reduce max log-weights)]
    [(map (fn [log-weight]
            ;; Avoid NaN and Infinity.
            (cond
              (= log-weight max-log-weight) 1. ; all are -Infinity
              (not (> log-weight (/ -1. 0.))) 0. ; NaN or -Infinity
              :else (Math/exp (- log-weight max-log-weight))))
          log-weights)
     max-log-weight]))

(defn resample
  "resamples particles proportionally to their current weights;
  returns a sequence of number-of-new-particles resampled
  particles with the weight set to the average weight"
  [particles number-of-new-particles]
  ;; Invariant bindings for sampling
  (let [;; The roulette wheel
        [weights max-log-weight] (particle-weights particles)
        total-weight (reduce + weights)
        step (/ total-weight (double number-of-new-particles))

        ;; After resampling, all particles have the same weight.
        log-weight (Math/log 1) #_(if (< (/ -1. 0.) max-log-weight (/ 1. 0))
                     (+ (Math/log step) max-log-weight)
                     ;; If the weight is not usable, set it
                     ;; to -Infinity so that the sweep dies out
                     ;; if possible.
                     (/ -1. 0.))

        ;; Particle sequence is circular.
        all-weights weights
        all-particles particles]

    ;; The systematic sampling loop
    (loop [x (rand total-weight)
           n 0      ; number of particles sampled so far
           acc 0    ; upper bound of the current segment
           weights all-weights
           particles all-particles
           new-particles nil]
      (if (= n number-of-new-particles)
        new-particles
        (let [[weight & next-weights] weights
              [particle & next-particles] particles
              next-acc (+ acc weight)]

          (if (< x next-acc)
            ;; Found the wheel segment into which x has fallen.
            ;; Advance x by step for the next particle's segment.
            (recur (+ x step) (+ n 1)
                   acc weights particles
                   (conj new-particles
                         (update-in particle [:state]
                                    set-log-weight log-weight)))
            ;; Otherwise, keep going through the particle's
            ;; segments, recycling the list of particles and
            ;; their weights when necessary.
            (recur x n
                   next-acc
                   (or next-weights all-weights)
                   (or next-particles all-particles)
                   new-particles)))))))
