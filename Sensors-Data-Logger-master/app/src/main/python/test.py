import random

def chooseText():
    choice = random.choice([True, False])
    if choice == True:
        return "ZXC"
    else:
        return "SOLO"
    return "2031"

if __name__ == "__main__":
    print(chooseText())
