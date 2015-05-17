(ns lunch-bot.yelp
  (:require [gws.yelp.client :as client]
            [gws.yelp.api :as api]
            [lunch-bot.send :as send]))

(def ^:private yelp-key (System/getenv "YELP_CONSUMER_KEY"))
(def ^:private yelp-consumer-secret (System/getenv "YELP_CONSUMER_SECRET"))
(def ^:private yelp-token (System/getenv "YELP_TOKEN"))
(def ^:private yelp-token-secret (System/getenv "YELP_TOKEN_SECRET"))

(def ^:private yelp-client
  (client/create yelp-key yelp-consumer-secret yelp-token yelp-token-secret))

;; Sort mode: 0=Best matched (default), 1=Distance, 2=Highest Rated
(def ^:private yelp-sort-options {:best 0 :distance 1 :highest-rated 2})

(def ^:private zipcode "07302")
(def ^:private default-params {:term "restaurants"
                               :location zipcode
                               :limit 10
                               :sort 0
                               :radius_filter 1000})

(defn- yelp-query
  [& {:keys [term location limit sort radius-filter]
      :or {term "restaurant" location zipcode limit 10 sort 0 radius-filter 1000}}]
  (api/search yelp-client {:term term
                           :location location
                           :limit limit
                           :sort sort
                           :radius_filter radius-filter}))

(defn- parse-single [result]
  (let [url (result :url)
        name (result :name)
        rating-img (result :rating_img_url)
        rating (result :rating)
        review-count (result :review_count)
        food-image (result :image_url)
        category (first (first (result :categories)))
        address (clojure.string/join " "((result :location) :display_address))
        phone (result :display_phone)]
    (send/send-attachment
     (send/yelp-branding name url category address rating-img review-count rating))))

(defn get-random [user task-description]
  (let [results (yelp-query :sort (rand-int 3))]
    (if (nil? (results :businesses))
      "There was a problem. Sorry."
      (parse-single (rand-nth (results :businesses))))))

(defn- convert-to-meters [increment units]
  (let [units (if (nil? units) "miles" (clojure.string/lower-case units))
        increment (Integer/parseInt increment)]
    ;;^might be a better way to hadle this 
    (cond
      (or (nil? increment) (nil? units)) nil
      (or (re-matches #"miles*" units) (= units "mi")) (* 1609 increment)
      (or (re-matches #"meters*" units) (= units "m")) (* 1 increment)
      (or (re-matches #"kilometers*" units) (= units "km")) (* 1000 increment)
      :else nil)))

(defn- sort-text-to-mode
  "Convert english sort criteria to yelp sort mode digit"
  [sort-text]
  (cond
      (nil? sort-text) nil
      (re-matches #"best" sort-text) (yelp-sort-options :best)
      (re-matches #"closest" sort-text) (yelp-sort-options :distance)
      (re-matches #"highest.rated" sort-text) (yelp-sort-options :highest-rated)
      :else nil))

(defn- parse-query [text]
  (let [[sort-text term increment units location]
        (rest (re-matches #"(.+\s)*(\w+) within (\d+) (\w+) of (.+)" text))

        radius (or (convert-to-meters increment units) (default-params :radius_filter))
        sort (or (sort-text-to-mode sort-text) (default-params :sort))]
    (if (nil? term) nil
        {:term term
         :radius_filter radius
         :location location
         :sort sort})))

;;TODO: location string or lat lng
;; (defn- get-by-query [user task-description params]
;;   (let [results (api/search yelp-client (merge default-params params))]
;;     (if (nil? (results :businesses))
;;       (do
;;         (println "yelp results w/o businesses:" results)
;;         (str "There was a problem. " (get-in results [:error :text] "Sorry.")))
;;       (parse-single (first (results :businesses))))))

(defn- get-by-query[params]
  (let [{term :term radius :radius_filter location :location sort :sort} params
        results (yelp-query :term term :radius_filter
                            radius :location location :sort sort)]
    ;;^this is still pretty ugly
    (if (nil? (results :businesses))
      (str "There was a problem. " (get-in results [:error :text] "Sorry.")))
    (parse-single (first (results :businesses)))))

(defn handle-query-request
  "Command structure -> [sort-text] [category] within [increment] [units] of [location]"
  [user command text]
  (let [parsed (pares-query text)]
    (if (nil? parsed) (str "Unable to parse " text)
    (get-by-query
                  {:term (parsed :term)
                   :radius_filter (parsed :radius_filter)
                   :location (parsed :location)
                   :sort (parsed :sort)})))) 
