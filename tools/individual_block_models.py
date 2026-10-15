"""Compatibility entry point for deterministic, individually authored block materials."""
from natural_block_art import cloudsea_models, crucible_models, catalogue

if __name__=='__main__':
    from polish_models import polish_cloudsea, polish_crucible
    polish_cloudsea();polish_crucible();catalogue()
