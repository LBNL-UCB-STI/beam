#!/usr/bin/env python
# coding: utf-8

# In[1]:


# Reads beam actor messages from the last create beam output dir
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


# A particular person messages
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

person4

