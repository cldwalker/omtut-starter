(ns omtut-starter.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [goog.events :as events]
            [cljs.core.async :refer [put! <! >! chan timeout]]
            [markdown.core :as md]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs-http.client :as http]
            [omtut-starter.utils :refer [guid]]))

(enable-console-print!)

(defn- with-id
  [m]
  (assoc m :id (guid)))

(defn- clear-nodes!
  [& nodes]
  (doall (map #(set! (.-value %) "") nodes)))

(defn- value-from-node
  [component field]
  (let [n (om/get-node component field)
        v (-> n .-value clojure.string/trim)]
    (when-not (empty? v)
      [v n])))

;; =============================================================================
;; Utils

(def app-state
  (atom {}))

(defn comment [{:keys [author text] :as c} opts]
  (om/component
   (let [raw-markup (md/mdToHtml text)]
     (dom/div #js {:className "comment"}
              (dom/h2 #js {:className "commentAuthor"} author)
              (dom/span #js {:dangerouslySetInnerHTML #js {:__html raw-markup}})))))

(defn comment-list [app]
  (om/component
   (dom/div #js {:className "commentList"}
            (into-array
             (map #(om/build comment app
                             {:path [:comments %]
                              :key :id})
                  (range (count (:comments app))))))))

(defn save-comment!
  [comment url]
  (go (let [res (<! (http/post url {:json-params comment}))]
        (prn (:message res)))))

(defn handle-submit
  [e owner app opts]
  (let [[author author-node] (value-from-node owner "author")
        [text text-node]     (value-from-node owner "text")]
    (when (and author text)
      (let [comment {:author author :text text}]
        (save-comment! comment (:url opts))
        (om/update! app [:comments]
                    (fn [comments] (conj comments (assoc comment :id (guid))))))
      (clear-nodes! author-node text-node))
    false))

(defn comment-form [app opts]
  (reify
    om/IRender
    (render [_ owner]
      (dom/form
       #js {:className "commentForm" :onSubmit #(handle-submit % owner app opts)}
       (dom/input #js {:type "text" :placeholder "Your Name" :ref "author"})
       (dom/input #js {:type "text" :placeholder "Say something..." :ref "text"})
       (dom/input #js {:type "submit" :value "Post"})))))

(defn fetch-comments
  [url]
  (let [c (chan)]
    (go (let [{{comments :comments} :body} (<! (http/get url))]
          (>! c (vec (map with-id comments)))))
    c))

(defn comment-box [app opts]
  (reify
    om/IInitState
    (init-state [_ owner]
      (om/update! app [:comments] (fn [] [])))
    om/IWillMount
    (will-mount [_ owner]
      (go (while true
            (let [comments (<! (fetch-comments (:url opts)))]
              (om/update! app #(assoc % :comments comments)))
            (<! (timeout (:poll-interval opts))))))
    om/IRender
    (render [_ owner]
      (dom/div #js {:className "commentBox"}
               (dom/h1 nil "Comments")
               (om/build comment-list app)
               (om/build comment-form app {:opts opts})))))

(defn omtut-starter-app [app]
  (reify
    om/IRender
    (render [_ owner]
      (dom/div nil
               (om/build comment-box app
                         {:opts {:url "/comments"
                                 :poll-interval 2000}})))))

(om/root app-state omtut-starter-app (.getElementById js/document "content"))
