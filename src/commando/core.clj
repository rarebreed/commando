(ns commando.core)

(defn items
  [m]
  (flatten (for [[k v] m] [k v])))
