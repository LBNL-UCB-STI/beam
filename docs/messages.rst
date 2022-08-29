
.. _beam-messages:

Auto Capturing Beam Messages
============================

Capturing most messages that BEAM Actors generate
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

BEAM uses triggerId to trace down all the actor interactions starting from TriggerWithId Event that is sent by the Scheduler. When developing a new message type, extend it from HasTriggerId trait in order to trace these messages.
In order to enable saving messages to csv files one needs to add the following property to beam.conf file::

   beam.debug.messageLogging=true

The files are saved in the iteration directories, ie $RUN_OUTPUT_DIR$/ITERS/it.0/0.actor_messages_0.csv.gz. There may be multiple of these files per iteration. For the scenario sf-light-1k about 400Mb of gzipped data generated.

Generated csv files
^^^^^^^^^^^^^^^^^^^

The files contains the following columns

+ type: one of the following types (transition, event, message)
+ sender_parent
+ sender_name
+ receiver_parent
+ receiver_name
+ payload: message content or previous state in case of type = transition
+ state: FSM data or next state in case of type = transition
+ tick: current tick
+ triggerId: the triggerId which started the message sequence (if available)

How to visualize them
^^^^^^^^^^^^^^^^^^^^^

VisualizingApp can convert these files to different diagrams. It creates `PlantUML`_ files that can be easily converted to pictures. You can run it with gradle with the following command::

$ ./gradlew :execute -PmainClass=beam.utils.protocolvis.VisualizingApp -PappArgs=["'--input', 'output/sf-light/sf-light_2021-05-03_10-50-55_cxc/ITERS/it.0', '--output', 'docs/uml/sequence1.puml', '--diagram-type', 'Sequence', '--force', '--person-id', '010900-2012001379980-0-560057'"]

Parameters
----------

+----------------+------------------------------------------------------------------+
| parameter      | values                                                           |
+================+==================================================================+
| *input*        | directory or file where to read message files                    |
+----------------+------------------------------------------------------------------+
| *output*       | the output file                                                  |
+----------------+------------------------------------------------------------------+
| *diagram-type* | Sequence | ActorAsState | AgentState | SingleActorAsState        |
+----------------+------------------------------------------------------------------+
| *force*        | allows to overwrite the output without prompt                    |
+----------------+------------------------------------------------------------------+
| *person-id*    | the person id which the message sequence should be generated for |
+----------------+------------------------------------------------------------------+

.. note:: For Sequence diagram it's always better to provide either a single limited size file as an *input* or a *person-id* for limiting the number of interactions in order to avoid creating a huge file.

Diagram types
-------------

Sequence
~~~~~~~~

Sequence diagrams display every line from the beam message csv files. Because of that one always needs to limit the number of messages processed. It can be done by providing a small file that is filtered down to interesting messages. And also it can be done via providing person-id as a parameter to VisualizingApp.

This is an example of a sequence diagram for a person.

.. image:: _static/uml/dia-sequence-person.png

This is an example of a sequence diagram for a ridehail agent. It is produced by filtering the ridehail agent messages with pandas and saving them ot a csv with the following script.

.. code-block:: python

    msg = pd.read_csv(f"../output/sf-light/{dir}/ITERS/it.0/0.actor_messages_0.csv.gz")

    agent_id = 'rideHailAgent-020700-2013000080675-0-5008509'

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
    ].to_csv("../docs/uml/ride-hail-agent.csv")

.. image:: _static/uml/dia-sequence-ridehail-agent.png

Actor Message State Diagram
~~~~~~~~~~~~~~~~~~~~~~~~~~~

It's not a real state diagram. It has actors as nodes and messages as edges between nodes.
This diagram is generated with setting parameter *--diagram-type=ActorAsState*. The picture of this diagram is pretty big and messy. It makes sense to filter the messages by providing --person-id parameter.

Single Actor Message State Diagram
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

It's diagram is similar to the previous one. But it uses only messages that go to/from a single actor (i.e. RideHailManager). This diagram is generated with setting parameter *--diagram-type=SingleActorAsState*. The *--output* parameter must point to a folder because the VisualizingApp generates multiple puml files (one for each actor).

This is an example of Actor Message state diagram for ChargingNetworkManager actor.

.. image:: _static/uml/dia-message-state-charging-network-manager.png


Agent State Diagram
~~~~~~~~~~~~~~~~~~~

This diagram is generated with setting parameter *--diagram-type=AgentState*. It's a real state diagram which shows agent states and transitions between them. The *--output* parameter must point to a folder because the VisualizingApp generates multiple puml files (one for each agent). This is an example state diagram

.. image:: _static/uml/dia-state-person.png

Converting puml files to pictures
---------------------------------

Converting puml files to pictures can be done in `a number of ways`_. The easiest way would be running the following command for multiple files::

$  java -jar docs/uml/plantuml.1.2017.18.jar -DPLANTUML_LIMIT_SIZE=16000 -o <PICTURE_OUTPUT_DIR> ./*

For a single file::

$ java -jar docs/uml/plantuml.1.2017.18.jar -DPLANTUML_LIMIT_SIZE=16000 single_file.puml

.. _PlantUML:  https://plantuml.com/
.. _a number of ways:  https://plantuml.com/en/running
