#!/usr/bin/env python
# coding: utf-8

# In[1]:


import numpy as np
import matplotlib.pyplot as plt
import pandas as pd
import scipy.cluster.hierarchy as sch
import util.beam_data as bd
from util.data import find_last_created_dir
from util.data import meaningful

schema = {
    "type": str,
    "sender_parent": str,
    "sender_name": str,
    "receiver_parent": str,
    "receiver_name": str,
    "payload": str,
    "state": str,
    "tick": int,
    "triggerId": pd.Int64Dtype(),
}
root = "../beam_root"
# beam.debug.messageLogging = true (in beam.conf)

msg_file_num = 0
def load_data(dir, it):
    return pd.read_csv(f"{dir}/ITERS/it.{it}/{it}.actor_messages_{msg_file_num}.csv.gz", dtype=schema)
# from IPython.core.display import display, HTML
# display(HTML("<style>.container { width:100% !important; }</style>"))
# beam_out_path = "beamville/rh-reservation-before-walking__2023-02-09_18-36-57_bjq" #good
beam_out_path = "beamville/rh-stop-early-reservation-default-manager__2023-02-14_19-50-15_kzc"   #bad
beam_out_path = None
beam_out = find_last_created_dir(f"{root}/output", level = 1, num = 0) if beam_out_path is None else f"{root}/output/{beam_out_path}"
print(f"Using beam out dir: {beam_out}")

ifields = ['type', 'sender_name', 'receiver_name', 'payload', 'state', 'tick', 'triggerId']

msg = load_data(beam_out, 0)
# msg = load_data('enroute-ci-stuck__2022-02-01_11-07-10_pxv', 0)
msg.head()


# In[2]:


person_id = '012202-2013000609715-0-5899892'
display(f"-- Person sequence: {person_id} --")

trigger_ids = msg[((msg['sender_name'] == person_id) & (msg['sender_parent'] != "population")) | ((msg['receiver_name'] == person_id) & (msg['receiver_parent'] != "population"))
                  | (msg['payload'].str.contains(f"/{person_id}#"))
]['triggerId'].unique()
trigger_ids = [id for id in trigger_ids if id >= 0]

person4 = msg[
    (
            ((msg['sender_name'] == person_id) & (msg['sender_parent'] != "population"))
            | ((msg['receiver_name'] == person_id) & (msg['receiver_parent'] != "population"))
            | (msg['triggerId'].isin(trigger_ids))
            | (msg['payload'].str.contains(f"/{person_id}#"))
    )
    # & (msg['payload'].str.startswith('Routing'))
]
# ].shape
# ].iloc[5]['payload']
# person4[person4['payload'].str.contains('Board')]
person4


# In[47]:


res1 = msg[msg['payload'].str.contains('RideHailResponse.+error: None', case=False)]
res1
# msg.tail()


# In[48]:


res1.iloc[0, :]['payload']


# In[4]:


# rh_agent = msg[msg['payload'].str.contains("StartLegTrigger(22500", regex=False)]
# display(rh_agent)
# rh_agent
msg[msg['triggerId'] == 83015]


# In[4]:


# RideHailManager interaction
rh = msg[(msg['sender_name'].str.contains("RideHailManager")) | (msg['receiver_name'].str.contains("RideHailManager"))]
# rh[rh['tick'] == 22500]
rh.loc[7200:9000]


# In[8]:


# msg[(msg['triggerId'].isin([2999])) | msg['payload'].str.contains('-228-L5', na=False) | msg['state'].str.contains('-228-L5', na=False)][ifields]
msg.iloc[63900:64000]


# In[21]:


msg.loc[1942245]['payload']


# In[21]:


msg[msg['payload'].str.contains('virtual-personalVehicle-2905')].iloc[1]['payload']


# In[7]:


# person4['receiver_parent'].unique()
# msg.loc[8848726]['payload']
msg[msg['payload'].str.startswith("CompletionNotice(1080911")]


# In[6]:


agent_id = 'rideHailAgent-047701-2013000697331-0-433937'
# agent_id = 'TransitDriverAgent-SF:7590302'
display(f"-- Ride Hail Agent sequence {agent_id} --")

trigger_ids = msg[(msg['sender_name'] == agent_id) | (msg['receiver_name'] == agent_id)]['triggerId'].unique()
trigger_ids = [id for id in trigger_ids if id >= 0]

allAgents = msg[
    (
            (msg['sender_name'] == agent_id)
            | (msg['receiver_name'] == agent_id)
            | (msg['triggerId'].isin(trigger_ids))
    )
]
allAgents[(
    ~((allAgents['sender_name'].str.startswith('rideHailAgent-') & (allAgents['sender_name'] != agent_id))
      | (allAgents['receiver_name'].str.startswith('rideHailAgent-') & (allAgents['receiver_name'] != agent_id))
      )
)
]
#.iloc[0:60]
    # ].shape
# ].to_csv("../docs/uml/ride-hail-agent.csv")
# ].iloc[5]['payload']


# In[7]:


msg[
    (
            (msg['receiver_name'] == 'RideHailManager')
            | (msg['sender_name'] == 'RideHailManager')
    )
]


# In[48]:


# msg[msg['triggerId'] < 0]["triggerId"].unique().size
# msg[(msg['triggerId'] < 0) & (msg['type'] != 'transition')]
# msg[(msg['triggerId'] < 0) & (msg['payload'].str.startswith("RoutingResponse"))]
msg[(msg['triggerId'] == 2999)][ifields]
# msg[msg['payload'].str.startswith("RoutingRequest([x=553972.4563090717][y=4180774")]
# msg[msg['sender_name'].str.contains("rideHailAgent-228-L5")]
# msg.loc[90833, 'payload']


# In[9]:


display("-- Special payload or other things --")

msg[
    # (msg['sender_parent'] == 'temp')
    # & (~msg['payload'].str.contains('RoutingRequest', na=False))
    # & (~msg['payload'].str.contains('MobilityStatusInquiry', na=False))
    (msg['payload'].str.contains('PlanEnergyDispatchTrigger', na=False))
    # & (msg['sender_name'] == '022802-2012001386215-0-6282252')
    # (msg['payload'].str.contains('RoutingRequest', na=False)) & (msg['triggerId'] == 8238)
    # (msg['receiver_parent'].str.contains('population', na=False))
    # (msg['receiver_parent'] == 'population')
    # (msg['sender_name'] == 'BeamMobsim.iteration' )
    ]

# msg


# In[ ]:


msg.loc[96167]['payload']


# In[ ]:


# set sender, receiver types
import re

pattern = re.compile("^\d+(-\d+){1,}$")


def detectActor(parent, name):
    isParentPopulation = parent == "population"
    isParentHousehold = isinstance(parent, str) and parent.startswith("population/")
    isTransitDriverAgent = name.startswith("TransitDriverAgent-")
    isRideHailAgent = name.startswith("rideHailAgent-")
    looksLikeId = bool(pattern.match(name))
    if (isTransitDriverAgent): return "TransitDriverAgent"
    if (isRideHailAgent): return "RideHailAgent"
    if (isParentPopulation and looksLikeId): return "Household"
    if (isParentHousehold and looksLikeId): return "Person"
    if (isParentHousehold and ~looksLikeId): return f"HouseholdFleetManager:{name}"
    return name


filtered_msg = msg[(msg['sender_parent'] != 'temp')].copy()
filtered_msg['msg_type'] = filtered_msg['payload'].str.extract(r'TriggerWithId\(([^()]+)')
filtered_msg.loc[filtered_msg['msg_type'].isnull(), 'msg_type'] = filtered_msg['payload'].str.extract(r'(\w+)\(?')[0]
filtered_msg['sender'] = filtered_msg.apply(lambda row: detectActor(row['sender_parent'], row['sender_name']), axis=1)
filtered_msg['receiver'] = filtered_msg.apply(lambda row: detectActor(row['receiver_parent'], row['receiver_name']),
                                              axis=1)
filtered_msg.groupby(["sender", "receiver", "msg_type"]).count()


# In[ ]:


filtered_msg[filtered_msg['triggerId'] < 0]['msg_type'].unique()
# filtered_msg[(filtered_msg['triggerId'] < 0) & (filtered_msg['msg_type'] == 'R5Network')]


# In[ ]:


# filtered_msg[filtered_msg['sender'].str.startswith('BeamMobsim')]
filtered_msg[(filtered_msg['type'] != 'transition')]
    .groupby(["sender", "receiver", "msg_type"])['type'].count()
    .reset_index().rename(columns={'type': 'count'})
    .to_csv('beam_messages_count.csv')


# In[ ]:


filtered_msg[(filtered_msg['type'] == 'transition')].groupby(['receiver', 'payload', 'state']).count()[['type', 'tick']]


# In[ ]:


# creates triggerId -> person_id dictionary
def clean(s):
    return set([x for x in s if x >= 0])


rec = filtered_msg[(filtered_msg['receiver'] == "Person")].groupby('receiver_name')['triggerId'].agg(set)
rec.name = "rec"
sen = filtered_msg[(filtered_msg['sender'] == "Person")].groupby('sender_name')['triggerId'].agg(set)
sen.name = "sen"
rs = pd.concat([rec, sen], axis=1)
rs['trigger_ids'] = rs.apply(lambda row: clean(row['sen'].union(row['rec'])), axis=1)
# rs['trigger_ids']['010100-2012000596480-0-179584']
dict1 = rs['trigger_ids'].to_dict()
id_to_person = {id: person_id for person_id, trigger_ids in dict1.items() for id in trigger_ids}
# id_to_person = {k:v for (k, v) in id_person_pair}
# id_to_person
len(id_to_person)


# In[ ]:


filtered_msg.iloc[146670:146690]


# In[ ]:


#Clustering persons

def create_dimension(row):
    is_sender_person = row['sender'] == 'Person'
    is_receiver_person = row['receiver'] == 'Person'
    if is_sender_person and is_receiver_person: return f"{row['msg_type']}_self"
    if is_sender_person: return f"{row['msg_type']}_{row['receiver']}"
    if is_receiver_person: return f"{row['sender']}_{row['msg_type']}"
    raise ValueError("no person involved")



interactions = filtered_msg[filtered_msg['type'] != 'transition'].copy()
interactions.loc[interactions['sender'] == 'Person', 'person'] = interactions['sender_name']
interactions.loc[interactions['receiver'] == 'Person', 'person'] = interactions['receiver_name']
interactions = interactions[~interactions['person'].isnull()]
interactions['dimension'] = interactions.apply(create_dimension, axis=1)
interactions = interactions[['person', 'dimension']]
interactions


# In[ ]:


persons = interactions.groupby(['person', 'dimension']).size().reset_index(name="num_msg").sort_values(['person', 'dimension'])
persons = persons.pivot(index='person', columns='dimension', values='num_msg')
persons.fillna(0, inplace=True)
persons


# In[ ]:


persons_interaction_present = np.sign(persons)


# In[ ]:


plt.figure(figsize=(8, 6), dpi=80)
dendrogram = sch.dendrogram(sch.linkage(persons_interaction_present, method  = "ward"))
plt.title('Dendrogram')
# plt.xlabel('Customers')
# plt.ylabel('Euclidean distances')
plt.show()


# In[ ]:


from sklearn.cluster import AgglomerativeClustering
hc = AgglomerativeClustering(n_clusters = 5, affinity = 'euclidean', linkage ='ward')

y_hc=hc.fit_predict(persons_interaction_present)


clusters = pd.DataFrame(y_hc, index=persons_interaction_present.index, columns=['cluster'])
shown = [
    '010600-2013000040063-0-1831035',
    '010200-2012000700304-0-6796854',
    '600900-2013000179752-0-8087176',
    '600300-2013000025013-0-200862',
    '010100-2012000596480-0-179582',
    '600800-2014001325153-3-578676',
         ]
display(clusters[clusters.index.isin(shown)])
display(clusters[clusters['cluster'] == 0])
clusters.groupby('cluster').size()

