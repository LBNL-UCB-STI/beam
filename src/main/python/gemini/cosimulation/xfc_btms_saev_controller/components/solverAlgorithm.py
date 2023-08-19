import logging
import cvxpy as cp

def solverAlgorithm(prob: cp.Problem, verbose = False):

    try:
        try:
            prob.solve(solver = 'ECOS', verbose = verbose, abstol = 1e-6, reltol = 1e-6, feastol = 1e-6, max_iters = 10000)
        except: 
            logging.warning("---- ECOS solver failed, trying OSQP ----")
            raise ValueError("ECOS solver failed") # --> brings this to the next loop, like this we have a dedicated logging message for ECOS
        if prob.status != 'optimal':
            logging.warning(" ---- ECOS solver status: " + prob.status)
            if prob.status == 'optimal_inaccurate':
                pass
            else:
                raise ValueError("ECOS solver status: " + prob.status)
    except:
        prob.solve(solver='OSQP', verbose = verbose)
        logging.warning('---- solved with OSQP | solver status: %s ' %(prob.status))

    # logging solver stats
    logging.info('SOLVER STATS: solver name: %s | solver status: %s | optimal value %s |iterations: %s | setup_time %s | solve_time %s' % (prob.solver_stats.solver_name, prob.status, prob.value, prob.solver_stats.num_iters, prob.solver_stats.setup_time, prob.solver_stats.solve_time))